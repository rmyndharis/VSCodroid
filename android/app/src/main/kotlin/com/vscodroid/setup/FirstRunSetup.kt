package com.vscodroid.setup

import android.content.Context
import com.vscodroid.BuildConfig
import com.vscodroid.R
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import com.vscodroid.util.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.Certificate
import java.util.Locale
import androidx.core.content.edit
import android.annotation.SuppressLint

/**
 * @param assetBytes how much the APK's asset tree weighs, and [largestAssetBytes]
 *   the biggest single file in it. Both are measured at build time, see
 *   `app/build.gradle.kts`, and both are parameters rather than direct reads of
 *   `BuildConfig` so that the storage pre-flight can be exercised against a tree
 *   of known size. A unit test compiles against whatever `src/main/assets` holds
 *   on the machine running it: the whole tree on a developer's checkout (575 MiB
 *   counted at the 1.139.1 pin), and empty directories on the CI runner, which
 *   stubs them (`.github/workflows/build.yml`, "Create minimal asset stubs").
 *   With an empty tree every branch of the pre-flight computes the same number,
 *   so a test that did not supply its own figures would pass there while
 *   distinguishing nothing.
 */
// Every preference write here commits on purpose. What each one records is a
// step of a run that a kill can interrupt at any moment, and apply()'s flush
// window is exactly the interval those writes exist to survive. Lint does not
// hold that line: ApplySharedPref flags a call to `Editor.commit()`, not the
// KTX `edit(commit = true)` most writes use. FirstRunPrefsCommitTest does.
//
// UsableSpace: the pre-flight deliberately asks what is free rather than what
// the platform would let this app allocate. getAllocatableBytes counts space it
// would clear by evicting other apps' caches, which is a promise about a device
// state, not about this one, and the figure the user is shown has to be the one
// the extraction will actually meet.
@SuppressLint("UsableSpace")
class FirstRunSetup(
    internal val context: Context,
    private val assetBytes: Long = BuildConfig.EXTRACTED_ASSET_BYTES,
    private val largestAssetBytes: Long = BuildConfig.LARGEST_ASSET_BYTES,
    // Injected for the reason the two above are, and it is not optional here: the
    // credit for `usr/` is capped at this figure, so a test that leaves it at a
    // build-time zero cannot make that credit non-zero at all, and every case
    // exercising the gate would agree with every other whatever the gate did.
    private val bundledUsrBytes: Long = BuildConfig.BUNDLED_USR_BYTES,
    // And the same for the extensions directory, which is the other capped
    // credit. Read straight from `BuildConfig` this one was a zero on every
    // machine that runs the tests, so the credit was pinned at zero and the whole
    // decision behind it -- which bytes of that directory are ours to claim --
    // could not be reached from the gate at all.
    internal val bundledExtensionBytes: Long = BuildConfig.BUNDLED_EXTENSION_BYTES,
) {
    internal val tag = "FirstRunSetup"
    private val prefs = context.getSharedPreferences("vscodroid_setup", Context.MODE_PRIVATE)

    /**
     * Where [reportProgress] sends its updates, backed by [progressSink] so that
     * the extraction reports to whichever screen is on show rather than to the
     * one that started it.
     *
     * An instance field is what it was, and [setupMutex] is what made that wrong.
     * The lock is process-wide, so a relaunch mid-extraction (locale, font scale,
     * display size, an mcc/mnc change while a new phone's SIM registers, none of
     * which SplashActivity declares) leaves the running unpack reporting into the
     * DESTROYED activity's closure -- which keeps that Activity and its whole
     * view hierarchy reachable for the rest of the run -- while the replacement
     * blocks on the lock for the same minutes with its bar at 0 and no way to
     * move it. A bar stuck at 0% for minutes is what makes someone force-quit,
     * and that costs the whole extraction.
     *
     * A resumed screen claims it through [attachProgress], including the latest
     * message and percent; [detachProgress] releases only that screen's sink.
     */
    var onProgress: ((message: String, percent: Int) -> Unit)?
        get() = progressSink
        set(value) { attachProgress(value) }

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
    internal var currentStep: String? = null

    /**
     * Why the first failed write of this run failed, or null if none has.
     *
     * Kept so the abort in [runSetupLocked] can name a cause. Without it the
     * thrown `IOException("could not unpack vscode-reh")` was the whole story and
     * [describeFailure] had nothing else to render, so the failure this subsystem
     * exists for -- a disk that fills partway through -- reached the user with no
     * mention of disk anywhere.
     *
     * The FIRST, not the last: extraction attempts every remaining file after one
     * fails, so the last cause is whatever the tail of the tree hit, and the
     * first is the one that describes what went wrong.
     */
    internal var firstWriteFailure: String? = null

    /**
     * Whether what is already on disk was written by an interrupted run of THIS
     * exact build, which is what makes [extractAssetFile] free to skip a file it
     * finds at the asset's own length.
     *
     * Two conditions, not one. The attempt marker names the build, and an install
     * that no earlier build ever completed under is what makes the tree beneath
     * that marker this build's alone; on an upgrade the marker is written over
     * the previous release's files, so it cannot license anything. The marker
     * carries the rest of it: replacing one that names a different build, or
     * starting with no marker over a non-empty uncompleted `server/` tree left
     * by a build that predates the marker, poisons it with [MIXED_TREE], so a
     * tree written by more than one unfinished run can never match. See the
     * comment where this is set.
     *
     * Set from [KEY_EXTRACTION_ATTEMPT] at the top of [runSetupLocked] and
     * nowhere else, so the per-launch reconcile from SplashActivity, which
     * reaches [reconcilePythonRuntime] without ever entering [runSetupLocked],
     * always writes. The reconcile that [runSetupLocked] itself makes, after the
     * `usr/` extraction, can skip on a retry of the same build, which is what
     * the flag is for.
     */
    internal var resumeSameBuild = false

    fun isFirstRun(): Boolean = setupIsStale(
        prefs.getString(KEY_VERSION, null),
        prefs.getInt(KEY_VERSION_CODE, 0),
        getCurrentVersion(),
        getCurrentVersionCode(),
        prefs.getLong(KEY_PACKAGE_UPDATE_TIME, 0),
        packageUpdateTime(),
    )

    /**
     * @param beforeUnpack called once, with the lock held, by the instance that
     *   is about to do the work, and never by one that waited only to find it
     *   done. Where SplashActivity takes its foreground hold: taken before the
     *   lock, the hold belonged to the waiter too, and a waiter is the one
     *   caller here that CAN be cancelled, because `Mutex.lock` suspends. A
     *   config change during the unpack creates a second Splash that parks
     *   here, and destroying that one threw it out of the lock with the unpack
     *   still writing, so its `finally` gave back a hold the winner was relying
     *   on. Handing the hold to whoever reaches this line keeps the release
     *   with the write.
     */
    suspend fun runSetup(beforeUnpack: () -> Unit = {}): SetupResult = setupMutex.withLock {
        // Two Splash instances can exist at once, each calling this from its
        // own lifecycleScope: MainActivity hands a VIEW launch back here while
        // a launcher-task Splash may be mid-setup, and a config-change relaunch
        // creates the replacement while the unpack the old instance started is
        // still running. The body is blocking I/O that never checks for
        // cancellation, so cancelling the loser does nothing: serialize
        // instead, and let whoever waited find the work already done. The
        // winner's markSetupComplete() flips isFirstRun() before the lock is
        // released.
        try {
            if (!isFirstRun()) return@withLock SetupResult.SUCCESS
            beforeUnpack()
            runSetupLocked()
        } finally {
            synchronized(progressLock) { lastProgress = null }
        }
    }

    internal data class ExtractionSpace(
        val serverBytes: Long,
        val usrBytes: Long,
        val extensionBytes: Long,
        val missingOverhead: Long,
        val largestExistingFile: Long,
        val skippedExtensionBytes: Long,
        val largestSelectedAssetBytes: Long,
    )

    /** Measures selected APK files, skipped extensions and allocation overhead without following links. */
    internal fun extractionSpace(): ExtractionSpace {
        val assets = context.assets
        val roots = assets.list("")?.toSet() ?: throw IOException("Could not list bundled assets")
        val bundledExtensions = if ("extensions" in roots) {
            assets.list("extensions") ?: throw IOException("Could not list extensions")
        } else {
            emptyArray()
        }
        val extensionsToExtract = if (bundledExtensions.isNotEmpty()) {
            bundledExtensionsToExtract(
                File(context.filesDir, "home/.vscodroid/extensions"),
                bundledExtensions.toList(),
            )
        } else {
            emptyList()
        }
        val destinations = listOf(
            "vscode-reh" to "server/vscode-reh",
            "usr" to "usr",
            "extensions" to "home/.vscodroid/extensions",
        ) + listOf("server.js", "process-monitor.js", "platform-fix.js", "dns-proxy.js", "xdg-open.js")
            .map { it to "server/$it" }
        var largestExistingFile = 0L
        var skippedExtensionBytes = 0L
        var largestSelectedAssetBytes = 0L

        fun measureLength(
            length: Long,
            destination: File,
            reusable: Boolean,
            skipped: Boolean,
        ): Pair<Long, Long> {
            if (skipped) {
                skippedExtensionBytes += length
                return 0L to 0L
            }
            largestSelectedAssetBytes = maxOf(largestSelectedAssetBytes, length)
            if (reusable && destination.isFile) {
                val credit = minOf(length, destination.length())
                largestExistingFile = maxOf(largestExistingFile, credit)
                return credit to 0L
            }
            val rounding = (EXTRACTION_BLOCK_BYTES - length % EXTRACTION_BLOCK_BYTES) % EXTRACTION_BLOCK_BYTES
            // Encrypted f2fs cannot inline file data into the inode block.
            return 0L to EXTRACTION_BLOCK_BYTES + rounding
        }

        lateinit var measureChild: (String, File, Boolean, Boolean) -> Pair<Long, Long>

        fun measureChildren(
            assetPath: String,
            children: Array<String>,
            destination: File,
            reusable: Boolean,
            skipped: Boolean,
        ): Pair<Long, Long> {
            val missing = !skipped && (!reusable || !destination.isDirectory)
            var credit = 0L
            var overhead = if (missing) EXTRACTION_BLOCK_BYTES else 0L
            for (child in children) {
                val skipChild = skipped || (assetPath == "extensions" && child !in extensionsToExtract)
                val measured = measureChild("$assetPath/$child", File(destination, child), missing, skipChild)
                credit += measured.first
                overhead += measured.second
            }
            return credit to overhead
        }

        // Open children first; Android's AssetManager fails open() on folders
        // with IOException, avoiding a list() syscall on every regular file.
        // Zero-length streams still check list() in case an entry is a folder.
        measureChild = { assetPath, destination, missingParent, skipped ->
            val reusable = !skipped && !missingParent && !Files.isSymbolicLink(destination.toPath())
            val opened = try {
                assets.open(assetPath)
            } catch (_: IOException) {
                null
            }
            if (opened != null) {
                val length = opened.use { it.available().toLong() }
                val children = if (length == 0L) {
                    try {
                        assets.list(assetPath)
                    } catch (_: IOException) {
                        emptyArray()
                    } ?: throw IOException("Could not list $assetPath")
                } else {
                    null
                }
                if (!children.isNullOrEmpty()) {
                    measureChildren(assetPath, children, destination, reusable, skipped)
                } else {
                    measureLength(length, destination, reusable, skipped)
                }
            } else {
                val children = assets.list(assetPath) ?: throw IOException("Could not list $assetPath")
                if (children.isEmpty()) {
                    throw IOException("Could not open or list $assetPath")
                }
                measureChildren(assetPath, children, destination, reusable, skipped)
            }
        }

        fun measureDirectoryRoot(assetPath: String, destination: File, missingParent: Boolean): Pair<Long, Long> {
            val children = assets.list(assetPath) ?: throw IOException("Could not list $assetPath")
            val reusable = !missingParent && !Files.isSymbolicLink(destination.toPath())
            if (children.isNotEmpty()) {
                return measureChildren(assetPath, children, destination, reusable, skipped = false)
            }
            val length = assets.open(assetPath).use { it.available().toLong() }
            return measureLength(length, destination, reusable, skipped = false)
        }

        val measured = destinations.filter { it.first in roots }.associate { (asset, destination) ->
            val target = File(context.filesDir, destination)
            val missingParent = !hasUnredirectedParent(target)
            val result = when (asset) {
                "vscode-reh", "usr" -> measureDirectoryRoot(asset, target, missingParent)
                "extensions" -> {
                    val reusable = !missingParent && !Files.isSymbolicLink(target.toPath())
                    if (bundledExtensions.isNotEmpty()) {
                        measureChildren("extensions", bundledExtensions, target, reusable, skipped = false)
                    } else {
                        val length = assets.open(asset).use { it.available().toLong() }
                        measureLength(length, target, reusable, skipped = false)
                    }
                }
                else -> measureChild(asset, target, missingParent, false)
            }
            asset to result
        }
        return ExtractionSpace(
            measured.filterKeys { it != "usr" && it != "extensions" }.values.sumOf { it.first },
            measured["usr"]?.first ?: 0,
            measured["extensions"]?.first ?: 0,
            measured.values.sumOf { it.second },
            largestExistingFile,
            skippedExtensionBytes,
            largestSelectedAssetBytes,
        )
    }

    /**
     * Removes the temporary files a killed extraction left in the trees it writes into, and answers the
     * bytes they held. A tree behind a link is left alone, as the extraction leaves it
     * ([hasUnredirectedParent]).
     */
    private fun sweepStrayExtractionTemps(): Long =
        listOf("server", "usr", "home/.vscodroid/extensions")
            .map { File(context.filesDir, it) }
            .filter { hasUnredirectedParent(it) }
            .sumOf { sweepStrayTempsUnder(it) }

    private suspend fun runSetupLocked(): SetupResult = withContext(Dispatchers.IO) {
        val previousVersionCode = getPreviousVersionCode()
        val currentVersionCode = getCurrentVersionCode()
        val isUpgrade = previousVersionCode > 0

        // Who wrote what is already on disk. An attempt that never reached
        // markSetupComplete() leaves this key naming its own build, so the next
        // attempt at the SAME build knows the files under it are its own and
        // [extractAssetFile] may skip the ones already at the right length. An
        // upgrade sees a different key and rewrites everything, which is the
        // point: equal length is not equal content.
        //
        // The key alone does not say that, which is what the first half of the
        // condition is for. The marker is written before the first byte, and the
        // previous release's files are already on disk by then, so on an upgrade
        // it stands over a tree the PREVIOUS build wrote: an attempt that stops
        // part way leaves a mixture, half this build and half the last, with
        // nothing in a length to tell the two apart. An install no earlier build
        // ever completed under is most of the answer, and it is not all of it,
        // because "no build ever COMPLETED here" is not "no OTHER build ever
        // WROTE here" and both keys are written only by markSetupComplete(): a
        // fresh install where v1 wrote half the tree and died, then updated to v2
        // before it ever finished, leaves both keys unset with v1's files on
        // disk. v2 records its own marker the moment it starts writing, and
        // without more than that it would hand its own retry a skip over v1's
        // bytes. runPreExtractionMigrations cannot catch that one either, being
        // gated on an upgrade this install does not look like.
        //
        // So the marker is poisoned rather than replaced when it names a
        // different build, or when no marker is stored over a non-empty
        // uncompleted `server/` tree: from then on it can never equal the
        // attempt it is compared against, and no retry is licensed until
        // markSetupComplete() clears it. The cost of the poison is one re-copy
        // of a tree written by more than one build, which is the answer that was
        // wanted anyway. An upgrade re-copies for the same reason, and there is
        // no cheaper answer without a per-file digest the build does not produce.
        //
        // Both keys, not the code alone, because the question is "has any build
        // ever completed here" and either key on its own answers something
        // narrower.
        //
        // Read here and WRITTEN below the storage pre-flight, which is the only
        // exit that returns without touching a byte. Written here it claimed a
        // tree the run had not written into, and the poison then fired on a
        // record nobody had earned: a fresh install refused for LOW_STORAGE under
        // v1 leaves v1's marker standing over an empty tree, and if the app
        // updates before the user frees space, v2 reads a marker naming another
        // build and poisons its own for ever. Every v2 retry then re-copies all
        // 810 MiB although v2 is the only build that ever wrote a byte, which is
        // exactly the user the skip exists for: someone who frees space in two
        // goes.
        val updatedAt = packageUpdateTime()
        val attempt = "${getCurrentVersion()}/$currentVersionCode" +
            if (updatedAt > 0) "/$updatedAt" else ""
        val nothingCompletedHere =
            prefs.getString(KEY_VERSION, null) == null && previousVersionCode == 0
        val storedAttempt = prefs.getString(KEY_EXTRACTION_ATTEMPT, null)
        resumeSameBuild = nothingCompletedHere && storedAttempt == attempt
        currentStep = null
        firstWriteFailure = null

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
            // The ICU libraries an update replaced, ahead of the pre-flight for the same
            // reason, and kept out of the migrations above because they are not tied to
            // an upgrade: it names only a library this APK carries under another major,
            // so a retry cannot take anything an earlier attempt of this build wrote.
            pruneSupersededIcu()

            // Pre-flight: enough room for the part of the tree that is not
            // already unpacked, plus what rewriting the rest of it costs.
            //
            // The asset total comes from the build rather than from a literal here,
            // see EXTRACTED_ASSET_BYTES in app/build.gradle.kts for why, and what is
            // already on disk is MEASURED rather than inferred from the tree being
            // there at all. Those two answers differ exactly where it matters: a
            // complete tree and a tree an interrupted attempt left half-written both
            // exist, and the first needs almost nothing while the second needs the
            // rest of itself. [requiredExtractionBytes] carries the arithmetic,
            // [extractionSpace] which APK files are already present, and
            // [sharedTreeCredit] the clamps on the two roots that are not ours
            // alone.
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
            var available = context.filesDir.usableSpace
            // Raw server bytes bound rewrite headroom and identify an unfinished
            // tree. Only matching APK paths below count as reusable bytes.
            var extractedTreeBytes = installedExtractionBytes(File(context.filesDir, "server"))
            val space = extractionSpace()
            val installed = space.serverBytes +
                sharedTreeCredit(space.usrBytes, bundledUsrBytes, 0) +
                sharedTreeCredit(space.extensionBytes, bundledExtensionBytes, 0)
            val selectedAssetBytes = (assetBytes - space.skippedExtensionBytes).coerceAtLeast(0)
            val selectedLargestAsset = if (space.skippedExtensionBytes > 0) {
                minOf(largestAssetBytes, space.largestSelectedAssetBytes)
            } else largestAssetBytes
            fun requiredBytes() = requiredExtractionBytes(
                selectedAssetBytes, selectedLargestAsset, installed,
                maxOf(extractedTreeBytes, space.largestExistingFile), space.missingOverhead,
            )
            var required = requiredBytes()
            // Short of room, and the caches are the one thing this app can give
            // back on its own. Reclaimed here rather than offered, because the
            // only route to the same action is a Command Palette command inside
            // a workbench that this refusal guarantees never loads: an updater
            // with months of npm cache was told to free 177 MB while several
            // hundred sat in `cacheDir/npm-cache`, on the same filesystem the
            // figure above was measured from, reachable by nothing on screen.
            // What is removed is a cache by construction (npm's, our tmp, crash
            // logs, the editor's logs), so the cost of being wrong is a
            // re-download, against an install that otherwise cannot start.
            //
            // The other thing it can give back is the temporary files a killed write left
            // in the trees extraction writes into, up to the size of the file that write
            // was making (92 MiB for the Copilot runtime). The sweep that removes them
            // runs at the first write into their directory, which comes after this check,
            // so a device short by about that much was told to free it while the file
            // stayed. A file that is gone no longer has to be rewritten, which the
            // headroom and the test for a tree an earlier build left both read, so the
            // tree is measured again.
            if (available < required) {
                val freed = StorageManager.clearCaches(context)
                val strays = sweepStrayExtractionTemps()
                if (strays > 0) {
                    extractedTreeBytes = installedExtractionBytes(File(context.filesDir, "server"))
                    required = requiredBytes()
                }
                available = context.filesDir.usableSpace
                Logger.i(
                    tag,
                    "Short of room for setup; reclaimed ${freed / 1_048_576}MB of caches and " +
                        "${strays / 1_048_576}MB of temporary files, ${available / 1_048_576}MB now free",
                )
            }
            if (available < required) {
                // What the user has to FREE, which is not what the unpack needs
                // to have free: the two differ by everything the device already
                // has. The message asks them to free this figure, so quoting the
                // demand told an updater 200 MB short to clear 873 and sent them
                // to delete photos they did not need to lose. Measured after the
                // cache reclaim above, so it is a shortfall against the device as
                // it now stands.
                lastShortfallBytes = required - available
                Logger.e(
                    tag,
                    "Insufficient storage: ${available / 1_048_576}MB available, " +
                        "${required / 1_048_576}MB required " +
                        "(${installed / 1_048_576}MB of the tree is already unpacked)",
                )
                return@withContext SetupResult.LOW_STORAGE
            }

            // Past the last exit that writes nothing, and before the first byte:
            // from here on this build owns whatever is under the marker. See
            // where [resumeSameBuild] is set for what the value means and why a
            // marker naming a different build is poisoned rather than replaced.
            //
            // commit(), not apply(): what follows is minutes of I/O and a kill in
            // the flush window would take the record with it, which is the same
            // reason markSetupComplete() commits.
            //
            // On an install where nothing has ever completed, a non-empty
            // `server/` tree with no `KEY_EXTRACTION_ATTEMPT` record means a
            // build that predates the marker (or lost its preference file)
            // already wrote into `server/` without finishing. Treating that tree
            // as mixed prevents a later retry from skipping equal-length files
            // whose bytes came from the unrecorded build, while an upgrade from
            // a completed install keeps a plain attempt marker.
            val unattributedServerTree =
                nothingCompletedHere && storedAttempt == null && extractedTreeBytes > 0L
            val marker =
                if ((storedAttempt == null && !unattributedServerTree) || storedAttempt == attempt) {
                    attempt
                } else {
                    "$attempt$MIXED_TREE"
                }
            prefs.edit(commit = true) { putString(KEY_EXTRACTION_ATTEMPT, marker) }

            reportProgress(context.getString(R.string.setup_step_directories), 2)
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
            // keyed on versionName, versionCode and package update time, so nothing tried again until
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
            //  - the retry is cheap in space, and cheap in time only on a fresh
            //    install. [extractAssetFile] skips a destination that already
            //    holds the asset's own length, but only when the previous attempt
            //    was this package ([KEY_EXTRACTION_ATTEMPT])
            //    and no earlier build ever completed here, because equal length is
            //    not equal content and an upgrade's marker stands over the
            //    previous release's files. Every upgrade attempt re-copies the
            //    whole tree, and there is no cheaper answer without a per-file
            //    digest the build does not produce.
            //  - a failure that is not about disk repeats exactly. A
            //    destination the write cannot use fails the same way every
            //    time, and the user gets "Setup failed" on each attempt with
            //    nothing telling them the retry is pointless.
            //  - the partial tree is left on disk when the user gives up, and
            //    deliberately: it is what makes the retry affordable. Deleting
            //    it would hand the next attempt the full figure again.
            val incomplete = mutableListOf<Pair<String, String>>()

            // The reh-web download carries the web client inside this same tree,
            // so this one extraction is both the server and the workbench.
            val serverMessage = context.getString(R.string.setup_step_server)
            reportProgress(serverMessage, SERVER_PROGRESS_START)
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
            val extracted = extractAssetDir(
                "vscode-reh",
                "server/vscode-reh",
                byteProgress(serverMessage, SERVER_PROGRESS_START, SERVER_PROGRESS_END, BuildConfig.BUNDLED_SERVER_BYTES),
            )
            if (!extracted) incomplete += "vscode-reh" to serverMessage

            val bootstrapMessage = context.getString(R.string.setup_step_bootstrap)
            reportProgress(bootstrapMessage, 60)
            // xdg-open.js is not a bootstrap script and is never required by one:
            // it is the program the execution trampoline runs for the command
            // `xdg-open`, and it lands here because this is where the trampoline's
            // table is told to look for it.
            for (script in listOf(
                "server.js", "process-monitor.js", "platform-fix.js", "dns-proxy.js", "xdg-open.js",
            )) {
                if (!extractAssetFile(script, "server/$script")) incomplete += script to bootstrapMessage
            }

            // The same byte counter, because this step is long enough to read as
            // a hang too: 2,812 files and 110 MiB with the bar pinned at 62 was
            // tens of seconds of nothing moving, which is exactly what the server
            // counter above was written to stop.
            val toolsMessage = context.getString(R.string.setup_step_tools)
            reportProgress(toolsMessage, USR_PROGRESS_START)
            val usrExtracted = extractAssetDir(
                "usr",
                "usr",
                byteProgress(toolsMessage, USR_PROGRESS_START, USR_PROGRESS_END, bundledUsrBytes),
            )
            if (!usrExtracted) incomplete += "usr" to toolsMessage

            if (incomplete.isNotEmpty()) {
                // With the cause, when a write reported one. The synthetic message
                // was all describeFailure had to render, so a device that filled up
                // told the user "could not unpack vscode-reh" and never the word
                // disk, on a screen whose only control is Retry.
                val cause = firstWriteFailure?.let { ": $it" }.orEmpty()
                currentStep = incomplete.first().second
                throw IOException("could not unpack ${incomplete.joinToString(", ") { it.first }}$cause")
            }
            // Extraction merges; it never removes. An upgrade that changes the
            // bundled Python therefore writes the new stdlib beside the old one
            // and leaves both, and an upgrade that bumps the bundled pip within
            // the same Python minor writes the new `pip-<version>.dist-info`
            // beside the old one. Here the runtime and `usr/` are already in
            // place, so this is the cleanup half.
            reconcilePythonRuntimeLocked()
            pruneSupersededBundledPipDistInfoLocked()
            if (isUpgrade) pruneDroppedBuiltInExtensions()

            reportProgress(context.getString(R.string.setup_step_git), 82)
            setupGitCore()

            reportProgress(context.getString(R.string.setup_step_symlinks), 85)
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
            createNpmWrappers()  // After createBashrc: appends npm functions to .bashrc
            createStorageSymlinks()
            createWelcomeProject()

            reportProgress(context.getString(R.string.setup_step_extensions), 88)
            extractBundledExtensions()

            reportProgress(context.getString(R.string.setup_step_environment), 97)
            createDefaultSettings()
            // A restore onto another phone looks exactly like this: no build has
            // completed setup here, because the preferences are not restored.
            if (nothingCompletedHere) withdrawRestoredEngineConsent()

            reportProgress(context.getString(R.string.setup_step_done), 100)

            // Before the flag, never after, and the order is the entire point.
            // markSetupComplete() commits, and a commit fsyncs, so without this
            // the record certifying an 875 MiB unpack is on the medium while the
            // unpack itself is still page cache. A power cut there leaves
            // isFirstRun() false over a tree with holes in it, and nothing on
            // device checks that tree again: no repair path reads content, and
            // only Clear Data or an app update re-extracts. See
            // [flushWritesToMedia] for what this does not buy.
            //
            // The user sees this as a pause on "Done!", on Dispatchers.IO,
            // behind a progress bar already at 100%.
            flushWritesToMedia()

            markSetupComplete()

            val elapsed = System.currentTimeMillis() - startTime
            Logger.i(tag, "First-run setup completed in ${elapsed}ms")
            logStorageUse(available, (selectedAssetBytes - installed).coerceAtLeast(0), space.missingOverhead)
            SetupResult.SUCCESS
        } catch (e: Exception) {
            Logger.e(tag, "First-run setup failed", e)
            lastFailure = describeFailure(currentStep, e)
            SetupResult.ERROR
        }
    }

    /**
     * Says in the log how much storage this run used against what the pre-flight allowed for.
     *
     * The overhead the pre-flight models (a node block per file, data rounded to 4 KiB, a block per
     * directory, for encrypted f2fs) was checked against one measurement taken by hand, and what it
     * asks for moves with every pin. Every first run is a measurement of it, on a filesystem this
     * project does not have in its test devices, and the line below is how one is read off a
     * device: it names the filesystem and goes to logcat (`adb logcat -s VSCodroid.FirstRunSetup`),
     * which Copy Bug Report does not carry. [before] is the free space the pre-flight read, after
     * its cache reclaim, and what is read here is a device that other apps were also writing to,
     * so a run that wrote less than [MEASURABLE_PAYLOAD_BYTES] says nothing and is not reported.
     */
    private fun logStorageUse(before: Long, payloadBytes: Long, modelledOverhead: Long) {
        if (payloadBytes < MEASURABLE_PAYLOAD_BYTES) return
        try {
            val used = before - context.filesDir.usableSpace
            val mounts = runCatching { File("/proc/mounts").readLines() }.getOrDefault(emptyList())
            val fileSystem = fileSystemOf(context.filesDir.canonicalPath, mounts)
            Logger.i(tag, storageUseReport(used, payloadBytes, modelledOverhead, fileSystem))
        } catch (e: Exception) {
            Logger.d(tag, "Could not measure the storage setup used: ${e.message}")
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
     * Alone among the directories above, this one can live outside filesDir: an
     * install that already had its projects on shared storage
     * (/storage/emulated/0/Android/data/<pkg>/files/projects) keeps them there,
     * which some routes outside the app can still reach (MTP over USB, a few OEM
     * managers) and which Clear Data wipes outright. A new install gets
     * filesDir/projects, because shared storage cannot hold a symbolic link and
     * npm dies on the first one; Environment.getProjectsDir decides which. The
     * rest are under filesDir, where nothing outside the app can reach them, so
     * they only ever need creating. This one can need repairing.
     *
     * Creating it once per version was not enough. isFirstRun() gates on
     * versionName, versionCode and package update time, so a folder deleted after setup stayed missing
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
     *   could not be written, or if the subtree could not be listed at all. An
     *   asset that is simply absent is not a failure -- several are, in builds
     *   that skip a download script -- so it answers true.
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
        required: Boolean = false,
    ): Boolean {
        // Known directory roots start here and list directly. Only assets.list()
        // throwing IOException falls back to extractAssetFile; a failure while
        // inspecting or copying a child stays on that child rather than winding
        // back here to treat the root as an absent optional file.
        val assets = try {
            context.assets.list(assetPath)
        } catch (e: IOException) {
            Logger.d(tag, "Treating $assetPath as file (not directory)")
            return extractAssetFile(assetPath, destPath, onBytes, required = required)
        } ?: run {
            Logger.w(tag, "Could not list the assets under $assetPath; treating it as unpacked would certify an empty tree")
            return false
        }
        if (assets.isEmpty()) {
            return extractAssetFile(assetPath, destPath, onBytes, required = required)
        }
        return extractAssetChildren(assetPath, destPath, assets, onBytes)
    }

    private fun extractAssetChildren(
        assetPath: String,
        destPath: String,
        assets: Array<String>,
        onBytes: ((Long) -> Unit)?,
    ): Boolean {
        val destDir = File(context.filesDir, destPath)
        if (!hasUnredirectedParent(destDir) || Files.isSymbolicLink(destDir.toPath())) {
            Logger.w(tag, "Not extracting assets through a redirected directory")
            // Named, because a link the user made is not cleared by Retry and
            // the abort would otherwise print only which tree was incomplete.
            if (firstWriteFailure == null) firstWriteFailure = "$destPath is, or sits behind, a symbolic link"
            return false
        }
        destDir.mkdirs()
        // Every child is attempted even after one fails, so the log names
        // all of them rather than only the first.
        var ok = true
        for (asset in assets) {
            if (!extractAssetChild("$assetPath/$asset", "$destPath/$asset", onBytes)) ok = false
        }
        return ok
    }

    private fun extractAssetChild(
        assetPath: String,
        destPath: String,
        onBytes: ((Long) -> Unit)?,
    ): Boolean {
        val opened = try {
            context.assets.open(assetPath)
        } catch (openError: IOException) {
            // Only open() failing may fall back to directory listing; an I/O
            // failure while inspecting or copying an opened stream fails only
            // this child so sibling entries are still attempted.
            return extractAssetListedDirectory(assetPath, destPath, onBytes, openError)
        }
        val availableBytes = try {
            opened.available()
        } catch (e: IOException) {
            closeStreamQuietly(opened)
            if (firstWriteFailure == null) firstWriteFailure = e.message
            Logger.w(tag, "Could not inspect asset $assetPath: ${e.message}")
            return false
        }
        if (availableBytes == 0) {
            val assets = try {
                context.assets.list(assetPath)
            } catch (_: IOException) {
                emptyArray()
            } ?: run {
                closeStreamQuietly(opened)
                Logger.w(tag, "Could not list the assets under $assetPath; treating it as unpacked would certify an empty tree")
                return false
            }
            if (assets.isNotEmpty()) {
                closeStreamQuietly(opened)
                return extractAssetChildren(assetPath, destPath, assets, onBytes)
            }
        }
        return try {
            extractAssetStream(opened, destPath, onBytes)
        } catch (e: IOException) {
            if (firstWriteFailure == null) firstWriteFailure = e.message
            Logger.w(tag, "Could not extract asset $assetPath: ${e.message}")
            false
        }
    }

    /** Descends into a listed directory child after `assets.open` rejects it as non-file. */
    private fun extractAssetListedDirectory(
        assetPath: String,
        destPath: String,
        onBytes: ((Long) -> Unit)?,
        openError: IOException,
    ): Boolean {
        val assets = try {
            context.assets.list(assetPath)
        } catch (listError: IOException) {
            if (firstWriteFailure == null) firstWriteFailure = listError.message
            Logger.w(tag, "Could not open or list $assetPath: ${listError.message}")
            return false
        }
        if (assets == null) {
            Logger.w(tag, "Could not list the assets under $assetPath; treating it as unpacked would certify an empty tree")
            return false
        }
        if (assets.isEmpty()) {
            if (firstWriteFailure == null) firstWriteFailure = openError.message
            Logger.w(tag, "Could not open listed asset $assetPath: ${openError.message}")
            return false
        }
        return extractAssetChildren(assetPath, destPath, assets, onBytes)
    }

    /** Closes [stream] when discarding an unused opening without masking an earlier result. */
    private fun closeStreamQuietly(stream: InputStream) {
        try {
            stream.close()
        } catch (_: IOException) {
        }
    }

    /**
     * @param sameLengthIsCurrent take a file already at the asset's own length as
     *   current, which [resumeSameBuild] alone permits only within one
     *   interrupted run. Equal length is not equal content, so the caller
     *   answers for the rest: [ensureExecPreload] passes it only for a file
     *   written since the package was last installed or updated.
     * @param required the asset was positively listed by its parent, so failure
     *   to open it is a failed extraction rather than an absent optional asset.
     * @return false when a required asset cannot be opened or a copy fails.
     */
    private fun extractAssetFile(
        assetPath: String,
        destPath: String,
        onBytes: ((Long) -> Unit)? = null,
        sameLengthIsCurrent: Boolean = false,
        required: Boolean = false,
    ): Boolean {
        // Opening the asset is separated from writing it so the two failures can
        // be told apart. They are not the same event: an absent optional asset
        // is routine in builds that skip a download script, while a
        // copy that starts and then fails is not, and the single catch this
        // replaced reported both as "Asset not found".
        val input =
            try {
                context.assets.open(assetPath)
            } catch (e: IOException) {
                if (required) {
                    if (firstWriteFailure == null) firstWriteFailure = e.message
                    Logger.w(tag, "Could not open listed asset $assetPath: ${e.message}")
                    return false
                }
                Logger.d(tag, "Asset not found: $assetPath (will be available after build)")
                return true
            }
        return extractAssetStream(input, destPath, onBytes, sameLengthIsCurrent)
    }

    private fun extractAssetStream(
        input: InputStream,
        destPath: String,
        onBytes: ((Long) -> Unit)? = null,
        sameLengthIsCurrent: Boolean = false,
    ): Boolean {
        val destFile = File(context.filesDir, destPath)
        var cause: String? = null
        val written = input.use { stream ->
            if (!hasUnredirectedParent(destFile)) {
                Logger.w(tag, "Not extracting an asset through a redirected parent")
                if (firstWriteFailure == null) firstWriteFailure = "$destPath is, or sits behind, a symbolic link"
                return false
            }
            destFile.parentFile?.mkdirs()
            // A retry of an interrupted run of the same build leaves the file
            // where it is when it already has the asset's own length.
            //
            // Nothing skipped anything before, so a Retry after a LOW_STORAGE
            // refusal or a failure at the 800th MiB re-copied all 23,000 files
            // from zero: minutes behind a bar the user has already watched once,
            // repeated for someone who frees space in two goes. `available()` on
            // an asset stream is the uncompressed length before any read, so this
            // costs one stat per file and no reading at all.
            //
            // Guarded on [resumeSameBuild] rather than done unconditionally, and
            // that guard is the whole of what makes it safe: equal length is not
            // equal content, and an upgrade whose new asset happens to weigh what
            // the old one did HAS to be written. The fast path is taken only by a
            // run whose predecessor was interrupted under the same package
            // identity AND on an install no earlier build ever completed under,
            // which is what makes the file under it this build's own rather than
            // the previous release's.
            //
            // It repairs rather than preserving damage. A file left truncated by
            // a kill or a full disk has a length that does not match, so the skip
            // passes it over and the copy runs.
            if (resumeSameBuild || sameLengthIsCurrent) {
                val assetLength = stream.available().toLong()
                if (assetLength > 0 && destFile.isFile && !Files.isSymbolicLink(destFile.toPath()) &&
                    destFile.length() == assetLength
                ) {
                    onBytes?.invoke(assetLength)
                    return true
                }
            }
            writeAtomically(destFile, onError = { cause = it }) { output -> stream.copyTo(output) }
        }
        // Reported from the destination rather than from copyTo's return, because the
        // write goes through a temporary file and a rename: a failed copy leaves the
        // destination as it was, and counting bytes that never landed would run the bar
        // past the end of a step that had not finished.
        if (written) onBytes?.invoke(destFile.length())
        if (!written) {
            if (firstWriteFailure == null) firstWriteFailure = cause
            Logger.w(
                tag,
                "Failed to write $destPath (${cause ?: "no cause reported"}); " +
                    "it keeps whatever it held before",
            )
        }
        return written
    }

    /**
     * Re-extracts Python when the interpreter in the APK no longer matches the
     * runtime in filesDir.
     *
     * The interpreter ships in the APK and every install replaces it. Its
     * runtime library and stdlib travel in assets and reach filesDir through
     * first-run extraction, which [isFirstRun] gates on versionName,
     * versionCode, and `PackageInfo.lastUpdateTime`. If `lastUpdateTime` cannot
     * be read or is unchanged when a rebuilt APK is installed in place without
     * a version bump, the install leaves a new interpreter next to the previous
     * runtime. Python then dies with
     * `CANNOT LINK EXECUTABLE ... library "libpython3.X.so" not found`, naming a
     * missing file rather than the install that removed it.
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
            // [StorageManager.deleteRecursive] and not File.deleteRecursively,
            // which asks isDirectory and listFiles and so walks through a
            // symbolic link to whatever it points at. The directory being
            // removed here is site-packages, which is where a person parks
            // `ln -s ~/projects/mylib` to work on a package while they use it,
            // so following one empties their project on the way past. The
            // helper unlinks a link and never descends it, for the same reason
            // the cache reclaim uses it.
            StorageManager.deleteRecursive(File(libDir, name), context.filesDir.parentFile)
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
            var available = context.filesDir.usableSpace
            if (available < required) {
                // A copy that was killed left its temporary file in this tree, and the write
                // that would remove it comes after this check.
                if (hasUnredirectedParent(libDir)) sweepStrayTempsUnder(libDir)
                available = context.filesDir.usableSpace
            }
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
            if (!extractAssetDir("usr/lib/python$version", "usr/lib/python$version", required = true)) {
                Logger.w(tag, "Python stdlib extraction did not finish; leaving the runtime pending")
                return
            }
            extractAssetFile("usr/lib/$runtime", "usr/lib/$runtime", required = true)
            // The missing runtime keeps the next launch's repair trigger true.
            if (!File(libDir, runtime).exists()) {
                Logger.w(tag, "Python runtime $runtime still missing after extraction; the APK assets look incomplete")
            }
        }
    }

    /**
     * Removes `pip-<old>.dist-info` directories left behind in the current
     * Python's `site-packages` when an update ships a newer bundled `pip` under
     * the same Python `3.<minor>`.
     *
     * `extractAssetDir` merges the shipped `pip/` package in place and writes
     * the new `pip-<new>.dist-info` alongside the previous release's
     * `pip-<old>.dist-info`. Left on disk, `pip list` and `pip check` warn on
     * every invocation that `pip` has multiple `.dist-info` directories.
     *
     * [supersededBundledPipDistInfo] refuses to remove anything unless the
     * shipped `pip-*.dist-info` is already present on disk, so an incomplete or
     * skipped extraction never deletes the only `pip` metadata directory left in
     * `site-packages`.
     */
    internal fun pruneSupersededBundledPipDistInfoLocked() {
        val runtime = pythonRuntimeInAssets() ?: return
        val version = PYTHON_RUNTIME_NAME.find(runtime)?.groupValues?.get(1) ?: return
        val assetPath = "usr/lib/python$version/site-packages"
        val shipped = runCatching { context.assets.list(assetPath)?.toSet() }.getOrNull()
        val sitePackages = File(context.filesDir, assetPath)
        val present = sitePackages.listFiles()?.map { it.name } ?: return
        for (name in supersededBundledPipDistInfo(present, shipped)) {
            Logger.i(tag, "Removing superseded bundled pip metadata $name")
            StorageManager.deleteRecursive(File(sitePackages, name), context.filesDir.parentFile)
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
    internal fun pythonRuntimeInAssets(): String? =
        try {
            context.assets.list("usr/lib")?.firstOrNull { PYTHON_RUNTIME_NAME.matches(it) }
        } catch (e: IOException) {
            Logger.w(tag, "Could not list usr/lib assets: ${e.message}")
            null
        }

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
     * documented API, carries no path this app has to keep up to date, and is
     * the same view the platform's own trust manager reads. Conscrypt names its
     * entries `system:<hash>.<n>` and `user:<hash>.<n>`. Measured on an API 33
     * emulator from inside this app's process, with one CA installed through
     * Settings: 126 aliases, of which exactly one began `user:`.
     *
     * Every alias, not only the `user:` half, and that is the point rather than
     * a widening. A root the device owner has turned off in Settings > Trusted
     * credentials leaves this store, and it does not leave
     * [systemCaCertificateDirs], which still holds the file. Filtering to
     * `user:` and copying that directory for the rest meant the bundle kept
     * trusting a root the owner had distrusted, and every other app on the phone
     * had stopped. That mattered less when only git read the bundle; SSL_CERT_FILE
     * and CURL_CA_BUNDLE now put it in front of python, ruby, node and curl too
     * (pip also reads the bundle for added CAs, but preloads its vendored certifi
     * snapshot alongside it, so a root still in that snapshot remains trusted by pip).
     *
     * The removal is recorded in `/data/misc/user/0/cacerts-removed`, which this
     * app cannot subtract for itself: the path is hardcoded, carries a user id,
     * and moved into an APEX on Android 14. Reading the merged store is how the
     * platform answers the same question, so it is what is asked.
     *
     * A `var` for the same reason as [systemCaCertificateDirs], and more
     * sharply: the provider does not exist on a JVM at all, so every test of
     * the bundle builder replaces this.
     */
    internal var deviceTrustedCertificates: () -> List<Certificate> = {
        val store = KeyStore.getInstance("AndroidCAStore")
        store.load(null)
        store.aliases().toList()
            .mapNotNull { alias -> runCatching { store.getCertificate(alias) }.getOrNull() }
    }

    /**
     * Puts the exec interceptor under `usr/lib` on any launch that finds it
     * missing, not the asset's length, or older than the package's last install
     * or update.
     *
     * The full `usr/` extraction already writes it on every version bump, so
     * this covers the two launches that extraction does not reach. A
     * same-version reinstall (`adb install -r` of a rebuilt APK, the everyday
     * dev case) runs no extraction, so the first build carrying the asset would
     * otherwise be installed over one without it, and [updateSettingsNativeLibPaths]
     * would find no file and write no setting; and a user who removes the file
     * by hand is one launch from a working terminal rather than one update,
     * whichever activity that launch enters. `MainActivity` runs this repair
     * too, and only this one: from `onCreate` for a callback intent that builds
     * a fresh instance, and from `onResume` for a launcher tap that brings a
     * live singleTask instance forward without creating it. Neither route
     * passes through SplashActivity. Measured 2026-09-23: a direct start of
     * MainActivity on API 33 and 36 emulators, and an icon tap on a live task
     * on API 33, each left a deleted file absent and every terminal dead with
     * CANNOT LINK EXECUTABLE until a cold launch.
     *
     * The length check matters as much as the existence check. A file left
     * truncated by a kill or a full disk passes `isFile`, and the linker aborts
     * every exec whose preload it cannot map, the shell included, so a partial
     * library is a dead terminal and not a degraded one. `isFile` alone was the
     * obvious guard and is the wrong one.
     *
     * Length alone is not enough either, because nothing else rewrites the file
     * on that same-version reinstall: a rebuilt library can keep the old one's
     * length, and one changed constant in `scripts/termux-exec.patch` did
     * (measured, 68,376 bytes both ways), so the length check alone kept the
     * old interceptor under the new build. A file older than the package's last
     * install or update is therefore rewritten whatever its length, once, since
     * the write moves its mtime past that time. When it is current that costs a
     * package lookup, a few stats of the file and an open of the asset for its
     * length, and no write.
     *
     * Ahead of the settings refresh in SplashActivity. The refresh writes the
     * LD_PRELOAD line only when this file is there, and that guard, not the
     * order, is what keeps the line from ever standing alone: a build that
     * shipped without the asset (a stale CI cache, a build that skipped
     * `scripts/build-termux-exec.sh`) writes nothing and degrades to today's
     * behaviour, and [extractAssetFile] answers an absent asset with a debug
     * line and true, which is exactly that. The order is what gets the file
     * and the line in the same launch rather than one launch apart.
     */
    fun ensureExecPreload() {
        val written = File(context.filesDir, Environment.EXEC_PRELOAD_ASSET).lastModified()
        extractAssetFile(
            Environment.EXEC_PRELOAD_ASSET,
            Environment.EXEC_PRELOAD_ASSET,
            sameLengthIsCurrent = written >= packageUpdateTime(),
        )
    }

    /**
     * When this package was last installed or updated, or 0 when that cannot be
     * read. An unknown current timestamp retains the version-based decision and
     * leaves [ensureExecPreload] to the length check alone.
     */
    private fun packageUpdateTime(): Long =
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        } catch (e: Exception) {
            0L
        }

    /**
     * Records that the app's old machine-settings preferences have been taken out.
     *
     * Committed like every other write in this class, although a lost write here
     * costs only one more regex pass over a small file on the next launch. The
     * record is written once per install, so waiting for the disk costs nothing
     * worth an exception to the rule the class header states.
     */
    internal fun markMovedDefaultsPruned() {
        prefs.edit(commit = true) { putBoolean(KEY_MOVED_DEFAULTS_PRUNED, true) }
    }

    /**
     * Whether the editor has to share a phone-width window rather than a tablet.
     *
     * `smallestScreenWidthDp` and not the current width, because it does not
     * change with rotation: the answer has to hold for the same device in
     * landscape, where a side bar that closed itself on every file would be
     * closing a bar there is room for. 600 is Android's own dividing line, the
     * one behind `sw600dp` resources.
     */
    internal fun isCompactScreen(): Boolean =
        context.resources.configuration.smallestScreenWidthDp < COMPACT_SCREEN_DP

    /**
     * The bundled extension identifiers this app recorded the last time it set
     * up extensions.
     *
     * Empty on an install that predates the record, which is not the same as
     * "this app has never bundled anything" but is the only honest reading
     * available: see [bundledIdsToRelist] for what that costs, once.
     */
    internal fun previouslyBundledIds(): Set<String> =
        (prefs.getStringSet(KEY_BUNDLED_IDS, emptySet()) ?: emptySet())
            .mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }

    /**
     * A defensive copy is required: [android.content.SharedPreferences.Editor.putStringSet]
     * documents that the set passed in must not be modified afterwards, and the
     * instance handed back by `getStringSet` must not be modified at all.
     */
    internal fun rememberBundledIds(ids: List<String>) {
        // Committed, as the class header requires of every write here: the next
        // upgrade reads this record to tell an uninstall from a first bundling,
        // and it is written moments before markSetupComplete() on a run a kill
        // can end at any line.
        prefs.edit(commit = true) { putStringSet(KEY_BUNDLED_IDS, HashSet(ids)) }
    }

    /**
     * Migrations that have to run *before* the assets are unpacked.
     *
     * Runs before extraction, and the ordering is not a detail: extraction merges
     * into whatever is already on disk and never deletes, so anything that
     * removes a stale tree has to happen first. Run afterwards it would delete
     * what was just unpacked, and the app would come up with no server at all.
     * That is also why there is no post-extraction migration hook: the one that
     * existed had an empty body for several releases, and a migration that
     * belongs after extraction can be given its own call site when there is one.
     *
     * It also runs before the storage pre-flight, which is a second ordering
     * constraint with its own reason, see the call site. Everything here only
     * deletes, so nothing it does depends on the room the pre-flight is
     * measuring.
     *
     * The pivot reclaim runs at most once per device, recorded in
     * [KEY_PIVOT_MIGRATED] rather than inferred from the version code, and that
     * is what makes a retry keep its progress. `previousVersionCode` is written
     * by [markSetupComplete], the last statement of the run, so every failed
     * attempt and every Retry still reads the pre-upgrade code. Without a record
     * of its own this deleted `server/vscode-reh` again on each attempt -- by
     * then the partly written NEW tree -- so the gate measured a freshly emptied
     * device, passed, and the unpack failed in the same place for ever, which is
     * the one thing the abort in [runSetupLocked] promises cannot happen.
     *
     * Recorded only when the trees are actually gone. A delete that failed leaves
     * the pre-built tree to be merged into, and skipping the next attempt's
     * removal would make that permanent.
     */
    private fun runPreExtractionMigrations(fromVersionCode: Int) {
        if (!hasUnredirectedParent(File(context.filesDir, "server/vscode-reh")) ||
            !hasUnredirectedParent(File(context.filesDir, "server/vscode-web"))
        ) {
            Logger.w(tag, "Keeping the previous server files: their parent directory is redirected")
            return
        }
        if (fromVersionCode < PIVOT_VERSION_CODE && !prefs.getBoolean(KEY_PIVOT_MIGRATED, false)) {
            var reclaimed = true
            // The server tree changed origin, not just version: what was there is a
            // pre-built VS Code Server, and what replaces it is Code - OSS built
            // from source. Their file sets differ (vsda and the bundled node are
            // gone, several paths moved), and extractAssetDir only ever writes over
            // what it recognises. Merging the two leaves orphans from the old tree
            // that nothing overwrites and nothing loads, with no visible symptom
            // beyond behaviour nobody can account for.
            val serverTree = File(context.filesDir, "server/vscode-reh")
            if (serverTree.exists() || Files.isSymbolicLink(serverTree.toPath())) {
                val freed = StorageManager.deleteRecursive(serverTree, context.filesDir.parentFile)
                if (!serverTree.exists() && !Files.isSymbolicLink(serverTree.toPath())) {
                    Logger.i(tag, "Removed the previous server tree (${freed / 1_048_576} MB)")
                } else {
                    // Not fatal on its own: extraction still writes the new tree over
                    // it. Say so loudly, because what survives is the orphan case
                    // above rather than a clean failure.
                    reclaimed = false
                    Logger.e(tag, "Could not remove the previous server tree; " +
                        "the new one will be merged into it")
                }
            }

            // Every pre-pivot release also extracted a standalone web client here
            // (the reh-web tree now carries it), and nothing writes or reads this
            // path anymore. Without this, tens of MB ride along on every phone
            // forever, counted into the storage figure the app reports.
            val webTree = File(context.filesDir, "server/vscode-web")
            if (webTree.exists() || Files.isSymbolicLink(webTree.toPath())) {
                val freed = StorageManager.deleteRecursive(webTree, context.filesDir.parentFile)
                if (!webTree.exists() && !Files.isSymbolicLink(webTree.toPath())) {
                    Logger.i(tag, "Removed the orphaned web client tree (${freed / 1_048_576} MB)")
                } else {
                    reclaimed = false
                    Logger.e(tag, "Could not remove the orphaned web client tree at $webTree")
                }
            }

            if (reclaimed) {
                // commit(), not apply(). apply() returns before the write reaches
                // disk, and everything after this line is minutes of extraction on
                // a device the system may reclaim at any point: losing the flag
                // makes the next attempt delete the partly written NEW server
                // tree, which is the exact failure this flag exists to prevent.
                // Already on Dispatchers.IO, so the synchronous write costs
                // nothing observable.
                prefs.edit(commit = true) { putBoolean(KEY_PIVOT_MIGRATED, true) }
            }
        }

        // On every upgrade, with no record of its own: it removes only names this
        // APK does not ship, so a retry cannot take anything an earlier attempt of
        // this build wrote. Until Code - OSS 1.139 the tree shipped the agent
        // host's Copilot CLI here, @github/copilot and copilot-linux-arm64, about
        // 175 MB, and earlier releases linked copilot-android-arm64 over it. The
        // tree no longer ships either package and patch 0020 keeps the host from
        // starting, so nothing loads them, and extraction would have kept them for
        // good. Ahead of the pre-flight they are measured as free space rather
        // than credited as tree already unpacked.
        pruneUnshippedServerEntries("vscode-reh/node_modules/@github")
        // The same for @microsoft/mxc-sdk's bin/: Windows, macOS and glibc
        // helpers nothing on Android can start, which server trees built before
        // build-vscode-oss.sh pruned them left on the device. About 19 MB in the
        // 1.133.0 tree that v1.1.0 to v1.4.0 shipped; the unreleased 1.138 and
        // 1.139 builds carried about 29 MB.
        pruneUnshippedServerEntries("vscode-reh/node_modules/@microsoft/mxc-sdk")
        // And @vscode/sandbox-runtime's vendor/seccomp, whose only content was an
        // x86-64 apply-seccomp; the APK now ships vendor/seccomp-src alone.
        pruneUnshippedServerEntries("vscode-reh/node_modules/@vscode/sandbox-runtime/vendor")
    }

    fun getPreviousVersionCode(): Int {
        return prefs.getInt(KEY_VERSION_CODE, 0)
    }

    private fun getCurrentVersionCode(): Int {
        return try {
            // longVersionCode unconditionally: it arrived in API 28 and minSdk
            // here is 29, so the deprecated field the old branch fell back to was
            // unreachable.
            context.packageManager.getPackageInfo(context.packageName, 0)
                .longVersionCode.toInt()
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Removes the built-in extensions an upgrade's server tree no longer carries.
     *
     * Extraction merges and never removes, and the server loads built-in extensions
     * by listing `server/vscode-reh/extensions`, so an extension a VS Code bump drops
     * or renames stayed on upgraded devices and loaded beside its replacement, with
     * both contributing the same views and editors. Upstream has renamed one before
     * (image-preview became media-preview).
     *
     * Nothing but extraction writes a top-level entry there; the Copilot aliases
     * go inside `extensions/copilot`, and extensions a user installs live under
     * `--extensions-dir`.
     */
    private fun pruneDroppedBuiltInExtensions() = pruneUnshippedServerEntries("vscode-reh/extensions")

    /**
     * Removes each entry directly under `server/<assetDir>` that the APK's own
     * `assetDir` does not list, for a directory only extraction writes to.
     *
     * Only top-level entries, and only against a listing that could be read: an
     * empty or unreadable listing removes nothing. `listFiles()` returns files as
     * well as directories, and both are removed, because an asset dropped between
     * builds could be either. [StorageManager.deleteRecursive] unlinks a link and
     * never descends it, so an alias goes without whatever it points at.
     */
    private fun pruneUnshippedServerEntries(assetDir: String) {
        val directory = File(context.filesDir, "server/$assetDir")
        if (!hasUnredirectedParent(directory) || Files.isSymbolicLink(directory.toPath())) return
        val shipped = try {
            context.assets.list(assetDir)?.toSet()
        } catch (e: IOException) {
            null
        }
        if (shipped.isNullOrEmpty()) return
        directory.listFiles()
            ?.filter { it.name !in shipped }
            ?.forEach {
                Logger.i(tag, "Removing server/$assetDir/${it.name}, which this build no longer ships")
                StorageManager.deleteRecursive(it, context.filesDir.parentFile)
            }
    }

    /**
     * Removes the ICU libraries in `usr/lib` that an update replaced.
     *
     * ICU is versioned by its soname, so `libicuuc.so.78` and `libicuuc.so.79` are two
     * files, and the Node runtime in the APK asks for exactly one of them. Extraction
     * writes what the APK carries and never removes, so an update across an ICU major
     * kept the previous three libraries, about 38 MB of which `libicudata` is 33, and
     * nothing loads them. No toolchain pack needs them either: none of the nine pack
     * ZIPs published for 1.5.0, 1.6.0 and 1.7.0 links ICU.
     *
     * The decision is [supersededIcuEntries], which names only a library this APK
     * carries under another major. An unreadable or empty listing removes nothing, a
     * `usr/lib` that is itself a link is not entered, and [StorageManager.deleteRecursive]
     * unlinks a link and never follows it. Safe before extraction for the same reason as
     * the migrations ahead of it: it only deletes, and only what this APK no longer
     * carries.
     */
    private fun pruneSupersededIcu() {
        val libDir = File(context.filesDir, "usr/lib")
        if (!hasUnredirectedParent(libDir) || Files.isSymbolicLink(libDir.toPath())) return
        val shipped = try {
            context.assets.list("usr/lib")?.toList()
        } catch (e: IOException) {
            null
        }
        if (shipped.isNullOrEmpty()) return
        val present = libDir.list()?.toList() ?: return
        for (name in supersededIcuEntries(present, shipped)) {
            Logger.i(tag, "Removing $name, which an update replaced")
            StorageManager.deleteRecursive(File(libDir, name), context.filesDir.parentFile)
        }
    }

    internal fun hasUnredirectedParent(file: File): Boolean = runCatching {
        val base = context.filesDir.canonicalFile
        val expected = File(base, file.relativeTo(context.filesDir).path).absoluteFile
        expected == expected.normalize() && expected.path.startsWith(base.path + File.separator) &&
            expected.parentFile.canonicalFile == expected.parentFile
    }.getOrDefault(false)

    /**
     * Records the setup that has just completed, and retires the attempt marker.
     *
     * commit(), not apply(). apply() returns before the write reaches disk, so a
     * kill in the flush window left isFirstRun() true after a successful 810 MiB
     * unpack and the next launch did all of it again. The window is milliseconds
     * and SharedPreferences flushes at activity stop, so it is unlikely; what it
     * costs when it lands is minutes, and this runs on Dispatchers.IO where the
     * synchronous write costs nothing observable.
     *
     * [KEY_EXTRACTION_ATTEMPT] goes in the same edit, so the record of a run in
     * flight cannot outlive the run: the skip it licenses is only ever for a
     * retry of an attempt that did not finish.
     */
    private fun markSetupComplete() {
        prefs.edit(commit = true) {
            putString(KEY_VERSION, getCurrentVersion())
            putInt(KEY_VERSION_CODE, getCurrentVersionCode())
            putLong(KEY_PACKAGE_UPDATE_TIME, packageUpdateTime())
            remove(KEY_EXTRACTION_ATTEMPT)
        }
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
        synchronized(progressLock) {
            currentStep = message
            lastProgress = message to percent
            progressSink?.invoke(message, percent)
        }
    }

    /**
     * An [extractAssetDir] byte counter that walks the bar from [from] to [to].
     *
     * One shape, three steps. The server tree had this inline and the two behind
     * it had nothing at all: `usr/` copied 2,812 files with the bar pinned at 62
     * and the extensions 3,787 with it pinned at 88, which is a fifth of the tree
     * spent on a bar that does not move -- exactly what the server counter was
     * written to prevent, on steps long enough to read as a hang.
     *
     * Reports only when the whole percent changes, so a step is tens of updates
     * rather than one per file. Capped at [to], because a tree fetched after the
     * APK was built weighs more than [total] and would otherwise run the bar into
     * the next step. Null when [total] is not known (the build injects a zero on
     * a runner with stubbed asset directories), which leaves the step silent
     * rather than dividing by it.
     */
    internal fun byteProgress(message: String, from: Int, to: Int, total: Long): ((Long) -> Unit)? {
        if (total <= 0) return null
        var done = 0L
        var shown = from
        val span = to - from
        return { bytes ->
            done += bytes
            val next = from + (done * span / total).toInt().coerceAtMost(span)
            if (next > shown) {
                shown = next
                reportProgress(message, next)
            }
        }
    }


    // Preference and extraction helpers for setup modules.

    internal fun extractAssetDirInternal(
        assetPath: String,
        destPath: String,
        onBytes: ((Long) -> Unit)? = null,
        required: Boolean = true,
    ): Boolean = extractAssetDir(assetPath, destPath, onBytes, required)

    internal fun hasMovedDefaultsPrunedKey(): Boolean = prefs.contains(KEY_MOVED_DEFAULTS_PRUNED)

    internal fun isMovedDefaultsPruned(): Boolean = prefs.getBoolean(KEY_MOVED_DEFAULTS_PRUNED, false)

    internal fun storedSetupVersion(): String? = prefs.getString(KEY_VERSION, null)

    @SuppressLint("ApplySharedPref", "UseKtx")
    internal fun recordPendingMovedDefaultsPrune(): Boolean =
        prefs.edit().putBoolean(KEY_MOVED_DEFAULTS_PRUNED, false).commit()

    internal fun retiredExtensionsSwept(): Set<String> =
        prefs.getStringSet(KEY_RETIRED_SWEPT, emptySet()) ?: emptySet()

    internal fun markRetiredExtensionsSwept(ids: Set<String>) {
        prefs.edit(commit = true) { putStringSet(KEY_RETIRED_SWEPT, HashSet(ids)) }
    }

    // Module delegates (preserve public, internal, and reflection-pinned signatures).

    fun setupOpensslConfig() = setupOpensslConfigImpl()

    fun setupGitCaBundle() = setupGitCaBundleImpl()

    fun setupGitCore() = setupGitCoreImpl()

    fun setupToolSymlinks() = setupToolSymlinksImpl()

    fun setupRipgrepVscodeSymlink() = setupRipgrepVscodeSymlinkImpl()

    fun setupCopilotAndroidAliases() = setupCopilotAndroidAliasesImpl()

    private fun setupSshDefaults() = setupSshDefaultsImpl()

    fun createNpmWrappers() = createNpmWrappersImpl()

    fun repairTruncatedSetupFiles() = repairTruncatedSetupFilesImpl()

    fun ensureToolchainEnvSourcing() = ensureToolchainEnvSourcingImpl()

    fun createBashEnvFile() = createBashEnvFileImpl()

    fun ensurePromptFix() = ensurePromptFixImpl()

    fun ensureStartupDirGuard() = ensureStartupDirGuardImpl()

    fun ensurePipConfig() = ensurePipConfigImpl()

    fun updateSettingsNativeLibPaths() = updateSettingsNativeLibPathsImpl()

    private fun migrateSettingsToMachinePath() = migrateSettingsToMachinePathImpl()

    private fun createWelcomeProject() = createWelcomeProjectImpl()

    fun createStorageSymlinks() = createStorageSymlinksImpl()

    private fun createBashrc() = createBashrcImpl()

    private fun createBashProfile() = createBashProfileImpl()

    private fun createTmuxConf() = createTmuxConfImpl()

    private fun createDefaultSettings() = createDefaultSettingsImpl()

    internal fun withdrawRestoredEngineConsent() = withdrawRestoredEngineConsentImpl()

    private fun extractBundledExtensions() = extractBundledExtensionsImpl()

    private fun generateExtensionsManifest(extensionsDir: File, bundledDirs: Array<String>) =
        generateExtensionsManifestImpl(extensionsDir, bundledDirs)

    private fun reconcileExtensionsManifest(
        manifestFile: File,
        extensionsDir: File,
        bundledDirs: Array<String>,
        bundledIds: List<String>,
        supersededDirs: List<String>,
    ) = reconcileExtensionsManifestImpl(manifestFile, extensionsDir, bundledDirs, bundledIds, supersededDirs)

    companion object {
        /**
         * Below this smallest width, in dp, the editor is sharing a phone screen.
         *
         * Android's own tablet threshold, the one that selects `sw600dp`
         * resources, so a device this app calls compact is a device the platform
         * calls compact too.
         */
        private const val COMPACT_SCREEN_DP = 600

        /**
         * The closure the running extraction reports through.
         *
         * In the companion beside [setupMutex] and for the same reason: the lock
         * is process-wide, so the run and the screen watching it need not belong
         * to the same [FirstRunSetup]. Volatile because it is written on the main
         * thread and read from Dispatchers.IO, which is what [lastFailure] and
         * `currentStep` are volatile for; as an instance field it was neither,
         * and stayed correct only through the happens-before edge that submitting
         * the coroutine happened to supply.
         */
        @Volatile
        private var progressSink: ((message: String, percent: Int) -> Unit)? = null

        private val progressLock = Any()
        private var lastProgress: Pair<String, Int>? = null

        /**
         * Claims the sink and replays the current run's latest update in order.
         *
         * This is a leaf lock: only field writes and the sink call belong here.
         * A sink must only post UI work, without waiting or taking another lock.
         */
        fun attachProgress(sink: ((message: String, percent: Int) -> Unit)?) {
            synchronized(progressLock) {
                progressSink = sink
                lastProgress?.let { sink?.invoke(it.first, it.second) }
            }
        }

        /**
         * Drops [sink] if it is still the installed one.
         *
         * Identity, not a blanket clear, and the case that needs it is two Splash
         * instances existing at once: a departing screen must not silence the
         * screen that replaced it, and one that has already been replaced has
         * nothing left to give back. Not the config-change relaunch, which runs
         * the other way round and destroys the old instance before it creates the
         * new one, so ordering alone would have covered that one.
         */
        fun detachProgress(sink: ((message: String, percent: Int) -> Unit)?) {
            synchronized(progressLock) {
                if (sink != null && progressSink === sink) progressSink = null
            }
        }

        /** How much of an exception message is worth putting on a splash screen. */
        internal const val DETAIL_LIMIT = 120

        /**
         * The longest tail kept after the only separator of a long message. One separator reads
         * as "where: why" as often as "what: detail", so the tail is kept only while it is short
         * enough to be a reason such as "No space left on device"; a longer one is the detail,
         * and the head names the step.
         */
        internal const val REASON_LIMIT = 48

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
                else -> {
                    val firstSeparator = message.indexOf(": ")
                    val reasonSeparator = message.lastIndexOf(": ")
                    val tail = if (reasonSeparator > 0) message.substring(reasonSeparator) else ""
                    val headBudget = DETAIL_LIMIT - 1 - tail.length
                    val keepsTail = reasonSeparator > firstSeparator ||
                        (reasonSeparator > 0 && tail.length <= REASON_LIMIT)
                    if (keepsTail && headBudget > 0) {
                        "$type: " + message.take(headBudget).trimEnd() + "\u2026" + tail
                    } else {
                        "$type: " + message.take(DETAIL_LIMIT).trimEnd() + "\u2026"
                    }
                }
            }
            return Failure(step?.trimEnd('.', ' ', '\u2026').orEmpty(), detail)
        }

        /** The band the server extraction reports across; the next step opens at 60. */
        private const val SERVER_PROGRESS_START = 5
        private const val SERVER_PROGRESS_END = 60

        /** The band `usr/` reports across; "Setting up git..." opens at 82. */
        private const val USR_PROGRESS_START = 62
        private const val USR_PROGRESS_END = 82

        /**
         * The band the bundled extensions report across; "Configuring
         * environment..." opens at 97.
         */
        internal const val EXTENSIONS_PROGRESS_START = 88
        internal const val EXTENSIONS_PROGRESS_END = 97

        private const val KEY_VERSION = "setup_version"
        private const val KEY_VERSION_CODE = "setup_version_code"
        private const val KEY_PACKAGE_UPDATE_TIME = "setup_package_update_time"

        /**
         * That the pre-pivot server tree has been reclaimed on this device.
         *
         * Separate from the two above because it answers a different question.
         * Those describe the setup that COMPLETED, and are written only when one
         * does; this describes a deletion that has already happened and must not
         * happen twice. See [runPreExtractionMigrations].
         *
         * Absent on every install that predates it, which reads as false and is
         * correct for both populations: a device past the pivot never enters the
         * branch that reads it, and one still on the pre-pivot tree has indeed
         * not had it reclaimed by a build that could record so.
         */
        private const val KEY_PIVOT_MIGRATED = "pivot_tree_reclaimed"

        /**
         * The package whose extraction is in flight, as
         * `versionName/versionCode/lastUpdateTime`. The timestamp is omitted
         * when unavailable, retaining the legacy identity for that case.
         *
         * Written below the storage pre-flight and before the first byte is
         * copied, and removed by [markSetupComplete], so its presence means an
         * attempt that began writing and did not finish. A run refused for
         * storage leaves it exactly as it found it, having written nothing to
         * claim. Together with an install no earlier build ever completed under,
         * it licenses [extractAssetFile] to skip a destination already at the
         * asset's own length: the files under it were then written by this same
         * build, so equal length is equal content in the only case it is trusted
         * for.
         *
         * On its own it does NOT say that, and this said it did. On an upgrade
         * the previous release's whole tree is already on disk when this is
         * written, so an attempt that stops part way records the new build over a
         * mixture, and the retry would keep every file of the old release whose
         * length happened to match. That is why the skip asks a second question,
         * and why replacing this marker with one naming a different build poisons
         * it with [MIXED_TREE] instead. See where [resumeSameBuild] is set.
         *
         * Both halves of the version and the package timestamp, because each
         * can move independently and identify different assets. Absent on every
         * install that predates it, which reads as "not this build" and rewrites,
         * and when `server/` already holds unattributed bytes on an uncompleted
         * install the first marker written over that tree is poisoned with
         * [MIXED_TREE] so a later retry cannot skip files the older build wrote.
         *
         * The package timestamp also distinguishes a rebuilt APK installed at
         * the same versionName and versionCode, so its files cannot inherit an
         * interrupted package's length-based skip.
         */
        private const val KEY_EXTRACTION_ATTEMPT = "extraction_attempt"

        /**
         * Appended to [KEY_EXTRACTION_ATTEMPT] once a second build has written
         * into the same unfinished tree, or when the first recorded attempt
         * starts over a non-empty uncompleted `server/` tree with no prior
         * marker, which is what makes the record unusable.
         *
         * The suffix, rather than a second preference key, because the question
         * the marker answers is already "did THIS build write everything below
         * me", and a value that can never equal the attempt it is compared
         * against answers it with no new state to keep in step. Nothing parses it
         * back off: [markSetupComplete] removes the key outright, so the poison
         * lives exactly as long as the unfinished tree it describes.
         */
        private const val MIXED_TREE = "!mixed"

        /**
         * Minimum working reserve for temporary writes and small setup files.
         * Missing files and directories additionally need block rounding and inode
         * blocks on encrypted f2fs. [extractionSpace] measures that
         * allowance from the APK. A separate margin covers generated setup files
         * and filesystem metadata beyond the measured file and directory nodes.
         * A complete-tree upgrade keeps its existing reserve because its nodes
         * and data blocks are already allocated.
         */
        private const val EXTRACTION_SLACK_BYTES = 128L * 1_048_576L
        private const val EXTRACTION_WORKING_MARGIN_BYTES = 16L * 1_048_576L
        private const val EXTRACTION_BLOCK_BYTES = 4_096L

        /**
         * How much the device was short the last time the pre-flight refused,
         * or 0 if it has not refused in this process.
         *
         * The figure depends on what is already unpacked, so it is no longer
         * something [storageToFreeMb] can compute on its own, and that
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
        private var lastShortfallBytes: Long = 0

        /** A run that wrote less than this tells nothing about allocation overhead, so it is not reported. */
        internal const val MEASURABLE_PAYLOAD_BYTES = 100_000_000L

        /**
         * The filesystem type that holds [path], from the lines of `/proc/mounts`, or "unknown".
         *
         * The longest mount point that is [path] or a parent of it, compared by whole path
         * components: `/data2` does not hold `/data/user`. Fields are separated by spaces, and a
         * space inside a mount point is written `\040`, which is undone before comparing.
         */
        internal fun fileSystemOf(path: String, mounts: List<String>): String {
            var best = ""
            var type = "unknown"
            for (line in mounts) {
                val fields = line.trim().split(Regex("\\s+"))
                if (fields.size < 3) continue
                val mountPoint = fields[1].replace("\\040", " ")
                val holds = mountPoint == "/" || path == mountPoint || path.startsWith("$mountPoint/")
                if (holds && mountPoint.length >= best.length) {
                    best = mountPoint
                    type = fields[2]
                }
            }
            return type
        }

        /**
         * The line a first run leaves about its own storage cost: what was used, what the files weigh,
         * the difference between the two, and what the pre-flight modelled that difference to be, in
         * decimal MB as every figure the user sees is.
         */
        internal fun storageUseReport(
            usedBytes: Long,
            payloadBytes: Long,
            modelledOverheadBytes: Long,
            fileSystem: String,
        ): String = "First-run setup used ${usedBytes / 1_000_000} MB of storage for " +
            "${payloadBytes / 1_000_000} MB of files on $fileSystem: " +
            "${(usedBytes - payloadBytes) / 1_000_000} MB beyond the files, " +
            "against ${modelledOverheadBytes / 1_000_000} MB modelled"

        /**
         * How much free space the next unpack needs, given what is already there.
         *
         * Three terms, and each one answers a different question:
         *
         *  - what is missing. Extraction merges into whatever is on disk, so an
         *    install already holding the tree is not about to write it again from
         *    nothing. Credit is capped at each matching APK file's length.
         *  - the room to rewrite one file, bounded by what is on disk to rewrite.
         *    [writeAtomically] writes a sibling temporary file and renames, so while the
         *    biggest file is being replaced both copies exist: the Copilot
         *    extension's `runtime.node`, 91.8 MiB at 1.139.1. On an install with
         *    nothing on disk there is no second copy to hold, and charging for one
         *    would refuse fresh installs that fit.
         *
         *    The bound is the smaller of the two, not a step from zero to the
         *    whole figure, because a tree can be present and still far too small
         *    to hold that file: an upgrade from before the pivot has just had
         *    `server/vscode-reh` deleted a few lines above and keeps only the two
         *    bootstrap scripts a pre-pivot release put beside it, about 34 KB.
         *    The step charged that device 113 MiB of headroom against 34 KB of
         *    rewritable bytes and refused installs that fit by roughly the width
         *    of the Copilot runtime. No file already on disk can cost more to
         *    replace than the bytes already on disk.
         *
         *    [extractedTreeBytes] bounds this separately from total storage credit.
         *    The caller combines existing server bytes with the largest matching
         *    file across all extraction roots: tools and bundled extensions still
         *    need atomic replacement when the server tree is missing. A small
         *    launch-time repair is charged only its own length, never the full
         *    size of the largest asset.
         *  - the larger of [EXTRACTION_SLACK_BYTES] and the missing allocation
         *    plus [EXTRACTION_WORKING_MARGIN_BYTES], so new entries fit encrypted
         *    f2fs without charging existing nodes again.
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
            missingAllocationOverhead: Long = 0,
        ): Long {
            val missing = (assetBytes - installedBytes).coerceAtLeast(0)
            val rewriteHeadroom = minOf(largestAssetBytes, extractedTreeBytes)
            return missing + rewriteHeadroom + maxOf(
                EXTRACTION_SLACK_BYTES,
                missingAllocationOverhead + EXTRACTION_WORKING_MARGIN_BYTES,
            )
        }

        /**
         * How much of a directory extraction shares with something else may be
         * credited as already unpacked.
         *
         * Toolchains and globally installed packages share `usr/`, and gallery
         * installs share the extensions directory. Only matching APK files may
         * contribute credit; unrelated files never reduce the missing payload.
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
         * A null [foreignBytes] means a caller that cannot work out its own
         * share, and yields no credit. It is not the same as zero: zero is the
         * claim that every byte in [installedBytes] is a byte extraction writes
         * over, and both callers earn that claim by counting only the paths the
         * APK carries rather than by asserting anything about the directory.
         * Handing the whole of a shared directory in with a zero here is what
         * credited a toolchain-filled `usr/` in full.
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
         * How much the user has to free, in whole MB, for the message shown to
         * them. Asking [FirstRunSetup] rather than repeating a literal in the
         * UI: a screen telling someone to free 500 MB when the device is 873
         * short sends them to clear space, come back, and fail in the same
         * place.
         *
         * The shortfall the refusal measured, not the size of the unpack. The
         * second is the figure a device with nothing free would have to reach,
         * and it is what the fallback answers for a caller asking before any
         * refusal has happened; there is none today, and over-stating is the
         * conservative direction for one that appears.
         */
        fun storageToFreeMb(): Long {
            val bytes = lastShortfallBytes.takeIf { it > 0 }
                ?: requiredExtractionBytes(
                    BuildConfig.EXTRACTED_ASSET_BYTES,
                    BuildConfig.LARGEST_ASSET_BYTES,
                    installedBytes = 0,
                    extractedTreeBytes = 0,
                )
            // Rounded up. Truncation was harmless while this over-stated the
            // answer by everything already free; against the shortfall itself it
            // names a figure up to a megabyte short of what the retry measures,
            // so the user frees exactly what was asked and is refused again by
            // the remainder, with nothing on screen to say why.
            //
            // Decimal, and the label says MB, so the two now agree. This divided
            // by 1_048_576 and called the result MB, which is the same defect the
            // rounding above exists to prevent, only smaller and harder to see: a
            // user who freed the number as their storage screen counts it freed
            // 4.6% too few bytes and was refused again. The unit is decided once,
            // for every screen, in StorageManager.formatSize.
            return (bytes + 999_999L) / 1_000_000L
        }

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

        /**
         * That [pruneMovedDefaults] has already taken the app's old preferences
         * out of the machine settings file on this install.
         *
         * A flag rather than a versionCode boundary, unlike [PIVOT_VERSION_CODE],
         * and the two are not the same shape of question. That one names a fixed
         * point in this project's history and can be compared against because the
         * history cannot change. This one asks "has this device been through the
         * pass yet", which no release number answers: expressed as
         * `previousVersionCode < 14` it was true on every launch while the app
         * sat at versionCode 13, so the pass meant to run once ran for ever, and
         * could only find lines the user had since chosen to write.
         */
        private const val KEY_MOVED_DEFAULTS_PRUNED = "moved_defaults_pruned"
    }
}
