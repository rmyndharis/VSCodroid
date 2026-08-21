package com.vscodroid.util

import android.os.StrictMode
import com.vscodroid.BuildConfig

/**
 * Makes main-thread disk I/O visible in a debug build, and only after launch.
 *
 * Installed from the tail of `MainActivity.onCreate`, deliberately not from
 * `VSCodroidApp.onCreate`. Everything before that point touches the filesystem on
 * the main thread on purpose and says so in place: the block of launch repairs in
 * `SplashActivity.onCreate` carries the comment "These touch the filesystem on the
 * main thread at launch", and it runs before the first frame. The volume is not a
 * handful either. `setupGitCore` alone does an lstat and usually a readlink per
 * entry over `assets/usr/lib/git-core/gitcore-symlinks`, which holds 146 lines
 * today, and it is one repair among more than a dozen. A policy installed at
 * process start therefore logs violations by the hundred on every single launch,
 * against code that already documents why each one is there, and a diagnostic that
 * noisy is one that gets deleted rather than read. Installed here it covers the
 * editor session instead, where the expected list below is short enough to act on.
 *
 * Network is not detected here, because the platform already detects it and kills
 * for it. `StrictMode.initThreadDefaults` calls `detectNetwork()` and
 * `penaltyDeathOnNetwork()` for any app whose targetSdk is Honeycomb or later,
 * and this app relies on that: the NetworkOnMainThreadException note in
 * `MainActivity.setupServiceCallbacks` is written around the readiness probe being
 * unable to run on this thread.
 *
 * That is exactly why the builder below is seeded from the policy already in force
 * rather than from a bare `Builder()`, and this is the one line here that is not
 * cosmetic. `Builder()` starts at mask 0 and `setThreadPolicy` assigns the mask
 * wholesale, so installing a freshly built disk-only policy would DELETE the
 * platform's network detection and its death penalty for the rest of the editor
 * session. Main-thread HTTP would then quietly succeed in a debug build and still
 * die in release, which is the worst arrangement available and the opposite of
 * what a diagnostic is for. `Builder(ThreadPolicy)` copies the existing mask and
 * every `detect`/`penalty` call is an OR onto it, so what is added below is added
 * and nothing is taken away.
 *
 * penaltyLog and nothing else. penaltyDeath fires from inside an arbitrary
 * framework call, and every violation a launch produces is deliberate, so death
 * would make the debug build unusable over design decisions nobody is going to
 * reverse. It would also fire for the
 * emoji font load that androidx.startup schedules through the InitializationProvider
 * in the merged manifest, which is not this repository's to fix. penaltyDialog
 * would put a dialog over the workbench during the very interaction being
 * investigated.
 *
 * What a cold launch actually produces, measured on an API 33 emulator rather
 * than predicted, so that a new line stands out against it. Thirteen violations,
 * every one a disk READ and not one a write:
 *
 *  - Eleven of the thirteen resolve `context.filesDir`, once per call to an
 *    `Environment.get*Dir`. `getFilesDir` stats the directory on every call, so
 *    each of `getLogsDir`, `getServerDir`, `getExtensionsDir`, `getProjectsDir`,
 *    `getSafMirrorsDir`, `getUserDataDir` and `getHomeDir` reports one.
 *  - Twelve of the thirteen originate in `NodeService`, not in the activity: two
 *    from `onCreate` and ten from the `launchServer` coroutine, which runs on
 *    `Dispatchers.Main` because this service confines its own state to that
 *    thread. `publishedResourceRoots` and `initBridge` do appear, but inside that
 *    coroutine's stack rather than on a path of their own, and third rather than
 *    first as an earlier version of this comment claimed.
 *  - The thirteenth is `MainActivity.folderFromUrl` from `onPageFinished`.
 *
 * Judged rather than left open, so nobody re-litigates it: none of the eleven is
 * worth removing. Memoising `filesDir` would take them all, and 37 tests across
 * 36 files stub `context.filesDir` with a temp directory of their own, in one
 * JVM, so a process-wide cache would hand the first test's tree to every test
 * after it. A stat per call is the cheaper of the two risks by a wide margin.
 *
 * Sites that did NOT fire on that launch, and which an earlier version of this
 * list named: `ProcessManager.readTokenFile`, `SafStorageManager.getPersistedFolders`,
 * `writeMemoryPressure` and the WebView rebuild. Each needs an interaction a cold
 * launch does not perform, so their absence says nothing about them.
 *
 * Anything not on that list is worth reading. What this cannot see is stated
 * rather than left to be discovered: a ThreadPolicy is per-thread, so nothing
 * arriving on the WebView's JavaBridge thread, on Dispatchers.IO, on the
 * `saf-writeback` thread or on the resource interceptor's thread is covered, and
 * neither is anything that runs before this call. For a launch-time inventory,
 * move the [install] call to `VSCodroidApp.onCreate` for one run and put it back.
 */
object MainThreadWatch {

    /**
     * Whether this build watches.
     *
     * `BuildConfig.DEBUG` and not `Logger`'s debuggable check, and the difference
     * is what keeps this out of release rather than merely quiet there. The
     * debuggable flag is read from ApplicationInfo at runtime, which R8 cannot
     * fold, so the StrictMode call would survive into the release DEX and a
     * re-signed debuggable release would install a policy in production. This
     * constant is `false` in release and the branch is gone with it.
     */
    internal fun shouldWatch(): Boolean = BuildConfig.DEBUG

    fun install() {
        if (!shouldWatch()) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder(StrictMode.getThreadPolicy())
                .detectDiskReads()
                .detectDiskWrites()
                .penaltyLog()
                .build()
        )
        Logger.i("MainThreadWatch", "Main-thread disk policy installed (log only)")
    }
}
