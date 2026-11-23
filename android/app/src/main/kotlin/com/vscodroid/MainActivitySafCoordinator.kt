package com.vscodroid

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebView
import android.widget.Toast
import com.vscodroid.util.AppNotices
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import com.vscodroid.util.StorageManager
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.vscodroid.util.Environment
import com.vscodroid.bridge.AndroidBridge
import com.vscodroid.storage.FolderBusyException
import com.vscodroid.storage.SafStorageManager
import com.vscodroid.storage.SafSyncEngine
import com.vscodroid.util.Logger
import com.vscodroid.util.MainThreadWatch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Opens the Android SAF folder picker UI.
 * Called from [AndroidBridge.openFolderPicker] via JS bridge.
 */
fun MainActivity.openFolderPicker() {
    // The bridge posts this, so it can run after this screen is gone: the
    // registry lets go of the launcher at destroy and launch then throws, and
    // nothing would be left to take the answer.
    if (isFinishing || isDestroyed) return
    // Guarded like the file chooser: a device with DocumentsUI disabled throws
    // here on the main thread, and an uncaught throw ends the process with the
    // server in it.
    try {
        folderPickerPending = true
        folderPickerLauncher.launch(null)
    } catch (e: ActivityNotFoundException) {
        folderPickerPending = false
        Logger.w(tag, "No document tree picker on this device", e)
    } catch (e: IllegalStateException) {
        folderPickerPending = false
        Logger.w(tag, "The folder picker is no longer registered", e)
    }
}

/**
 * Starts watching a mirror the workbench opened without going through Kotlin.
 *
 * VS Code switches folders by navigating its own WebView: Open Recent, the
 * Get Started list and Open Folder all build a `?folder=` URL and load it.
 * The picker is the only thing that ever started a watcher, so a folder
 * reached any of those ways was served read-write with nothing syncing it.
 * Every save, every `git checkout`, every terminal write stayed in the mirror
 * and never reached the device folder, and nothing on screen said so. Older
 * launch reclamation could also delete the mirror after its grant lapsed.
 *
 * A mirror whose folder has no grant any more cannot be adopted: the workbench
 * still lists it in Open Recent after the recent list drops it and releases the
 * grant. That one is said on screen instead, since edits there stay in the copy
 * until the folder is picked again, which syncs it and uploads the newer files.
 *
 * Three things are checked before acting, and each excludes a different way
 * of doing this twice: a path that is not under any mirror is an ordinary
 * project folder; a mirror already watched needs nothing; and a folder this
 * activity is itself mid-sync on will start its own watcher when it finishes.
 */
internal fun MainActivity.adoptWorkbenchFolder(folderPath: String) {
    lifecycleScope.launch {
        // Off the main thread, because this arrives from onPageFinished: the
        // lookup asks the system server for every persisted grant and prunes
        // the recent list against the answer, and a cold start whose remembered
        // workspace is a mirror reaches it with the workbench already drawn.
        //
        // The note is disk work beside it: a page on the copy's named path is
        // recorded, and a page on its hash path gets a named path to move to.
        val folder = withContext(Dispatchers.IO) {
            safManager.folderForOpenedPath(folderPath)
                ?.also { safManager.noteOpened(folderPath, it) }
        }
        val mirror = SafStorageManager.mirrorNameFor(
            folderPath, Environment.getSafMirrorsDir(this@adoptWorkbenchFolder),
        )
        if (folder == null) {
            if (mirror != null && mirror != ungrantedMirrorNoticed) {
                Logger.w(
                    tag,
                    "The workbench opened device folder copy $mirror, which has no grant; nothing syncs it",
                )
                AppNotices.showNotice(this@adoptWorkbenchFolder, R.string.saf_permission_expired, Toast.LENGTH_LONG)
            }
            ungrantedMirrorNoticed = mirror
            return@launch
        }
        ungrantedMirrorNoticed = null
        // Back on the main thread, which is where both of these are written:
        // the coroutine resumes on Dispatchers.Main.immediate, and a second
        // page load cannot interleave between the resume and openSafFolder's
        // own assignment because that assignment is made before it suspends.
        if (watchedSafFolder?.first?.path == folder.mirrorPath) return@launch
        if (syncingFolder == folder.uri) return@launch
        Logger.i(tag, "Adopting a device folder the workbench opened on its own")
        openSafFolder(folder.uri, navigate = false, adoptedPath = folderPath)
    }
}

/**
 * Handles a SAF folder selection result:
 * 1. Persists the URI permission
 * 2. Syncs folder contents to a local mirror (with progress dialog)
 * 3. Reloads VS Code with the mirror path
 */
internal fun MainActivity.handleSafFolderSelected(uri: Uri) = openSafFolder(uri, navigate = true)

/**
 * Syncs a device folder down, starts watching it, and points the editor at it.
 *
 * @param navigate whether to load the mirror in the WebView afterwards. False
 *   when the workbench has already navigated there itself, which is how Open
 *   Recent, the Get Started list and Open Folder reach a mirror: reloading
 *   would throw away the page the user just opened.
 */
internal fun MainActivity.openSafFolder(uri: Uri, navigate: Boolean, adoptedPath: String? = null) {
    if (!safManager.claimMirrorOpen(safManager.getMirrorDir(uri))) {
        AppNotices.showNotice(this, R.string.saf_folder_unavailable, Toast.LENGTH_LONG)
        return
    }
    // The mirror's name and never the tree URI. A SAF tree URI spells the
    // user's own directory (`.../tree/primary%3ADocuments%2F<folder>`),
    // `Logger.i` is not gated on a debuggable build, and logcat is readable
    // by anything holding READ_LOGS as well as by whoever a device bug report
    // is sent to. `persistPermission` in the hop below already names this same
    // folder by the six-byte digest of that URI, which is stable and is not
    // reversible; saying it in full here put the value back in logcat by
    // another route. The digest is a hash of the URI string and nothing else,
    // so it is answerable before any permission has been taken.
    Logger.i(tag, "Opening the device folder ${safManager.getMirrorDir(uri).name}")

    // Up before the provider is asked anything, and named from the tree URI
    // until it answers. Everything that used to run ahead of this dialog was a
    // cross-process round trip: queryDisplayName asks the provider for the
    // folder's display name (passed into persistPermission below so the tree is
    // queried once), and persistPermission reads the persisted grants to prune
    // the recent list. All three callers are on the thread that draws the
    // screen (the picker result, the bridge through runOnUiThread, and
    // onPageFinished through adoptWorkbenchFolder), so against a network- or
    // MTP-backed provider that was seconds of frozen editor with nothing on it
    // to say why. The real name replaces the placeholder below.
    val dialog = AlertDialog.Builder(this)
        .setTitle(getString(R.string.saf_sync_title))
        .setMessage(getString(R.string.saf_sync_message, treeUriLabel(uri.lastPathSegment)))
        .setCancelable(false)
        .create()

    // Marked before anything suspends, and that timing is the marker's whole
    // job: the two page-finished callbacks a folder switch produces arrive
    // back to back, and the second has to find this already set.
    syncingFolder = uri

    lifecycleScope.launch {
        // One open at a time, in the order they were asked for, and released
        // in the finally below however this ends. Everything that touches
        // the engine sits between the two, the failure handlers included, so
        // a watcher put back after a failed switch cannot interleave with
        // the next open stopping it. A lock() cancelled while it waits is
        // never held, so there is nothing to release on that path either.
        deviceFolderOpens.lock()
        // Read once the lock is held rather than when the open was asked for:
        // the open ahead of this one is what decides which watcher a failure
        // here has to put back.
        val previouslyWatched = watchedSafFolder
        try {
            // An adoption answers a page load, and the page may have moved on
            // while this waited its turn: the open ahead of it navigated (a
            // picked folder always does), or the workbench opened a third.
            // Syncing the folder it was queued for would then take the only
            // watcher off the folder on screen, which is the state adoption
            // exists to end. Decided inside the try so the finally releases
            // both the marker and the lock.
            if (adoptionIsStale(
                    navigate,
                    watchedSafFolder?.first?.name,
                    openWorkspaceFolder,
                    adoptedPath,
                    safManager.getMirrorDir(uri).name,
                )
            ) {
                Logger.i(tag, "Not syncing a device folder the page has moved on from")
                return@launch
            }

            // Shown once it is this open's turn rather than when it was asked
            // for. Put up at once, a second dialog stacked over the one still
            // reporting the first sync's progress, and the one on top named a
            // folder nothing was copying yet.
            dialog.show()
            trackDialog(dialog)

            // NonCancellable, and for the permission alone. The grant used to be
            // taken on the way in, so it was taken whatever happened next; a
            // plain hop here would drop it if this activity were destroyed in
            // the moment after the picker returned, and the folder would be
            // missing from the recent list with nothing to explain it. The sync
            // below must still be skipped in that case, which the ordinary hop
            // after this one does by throwing.
            val (answeredName, copyIsNew) = withContext(NonCancellable + Dispatchers.IO) {
                val queriedName = safManager.queryDisplayName(uri)
                safManager.persistPermission(uri, queriedName)
                // Asked before the sync below makes the copy. A copy older than this
                // open may have been opened by its hash path, which the workbench
                // keeps its own state under; see deviceFolderTarget.
                queriedName to !safManager.getMirrorDir(uri).exists()
            }
            // The name the provider gave, or the tree's own last segment where it did
            // not. The dialog says either, but only the first is a name: the second is
            // a document id, which names no link below.
            val displayName = answeredName ?: SafStorageManager.fallbackDisplayName(uri)
            dialog.setMessage(getString(R.string.saf_sync_message, displayName))

            // Before the sync, and this call was moved rather than added:
            // there used to be one after it, which `startWatching` has since
            // made redundant by stopping the previous watcher itself. Reading
            // that redundancy as the whole problem is the trap, because the
            // stop that was missing is this one.
            //
            // Reopening a folder that is already open (which
            // openRecentSafFolder routes through here) ran the initial sync
            // under that folder's live watcher. `copyDocumentToLocal` lands
            // each file by writing a `.partial` beside it and calling
            // `renameTo`, the observer reads `MOVED_TO` as CREATE, and every
            // file just pulled down was immediately queued to be pushed back.
            //
            // What happens to it if the sync fails depends on which folder
            // failed, and restoreWatcherAfterFailure is where that is
            // decided. What changes in the folder it stops watching is
            // recorded from here, and sent if that watcher is put back.
            //
            // Off this thread, and the wrapper below is what made that easy
            // to miss: only the sync was ever inside it, while this call and
            // the pair after it read as confined by sitting beside one that
            // was. Both are expensive on the main thread and the progress
            // dialog is already up. Stopping joins the previous folder's
            // write-back drain for up to two seconds and the interrupt
            // cannot shorten it, because the provider stream it is inside is
            // not interruptible; starting walks the whole mirror and issues
            // one inotify registration per directory, up to the engine's cap
            // of 2048.
            //
            // Plain Dispatchers.IO rather than NonCancellable: a scope
            // cancelled at this point must skip the sync as well, and the
            // hop throwing is what does that.
            withContext(Dispatchers.IO) { safManager.stopFileWatcher() }
            watchedSafFolder = null

            // At most one redraw per SYNC_PROGRESS_INTERVAL_MS, plus the last
            // file whatever the clock says. The engine reports once per file,
            // and a folder with tens of thousands of them posted that many
            // getQuantityString lookups and setMessage calls onto the main
            // looper, each one a measure and layout pass on the thread the
            // user is waiting on, for text changing far faster than anyone can
            // read it. The throttle is here rather than in the engine because
            // the engine has no clock policy and every other consumer of that
            // callback would inherit this one.
            //
            // A plain var needs no synchronisation: `initialSync`'s phase-2
            // loop is sequential on this one IO thread, so the callback has a
            // single caller.
            var lastProgressAt = 0L
            val mirrorDir = withContext(Dispatchers.IO) {
                safManager.syncToLocal(uri) { done, total ->
                    val now = SystemClock.elapsedRealtime()
                    if (!syncProgressIsDue(done, total, now - lastProgressAt)) {
                        return@syncToLocal
                    }
                    lastProgressAt = now
                    runOnUiThread {
                        dialog.setMessage(
                            resources.getQuantityString(
                                R.plurals.saf_sync_progress, total, displayName, done, total
                            )
                        )
                    }
                }
            }

            withContext(Dispatchers.IO) { safManager.startFileWatcher(mirrorDir, uri) }
            // On the main thread, deliberately, unlike the call above it.
            // [watchedSafFolder] has one writer thread by contract and this
            // is it; the work worth moving is the tree walk, not the two
            // assignments that record it.
            beginWatching(mirrorDir, uri)


            dialog.dismiss()

            // Reload VS Code with the mirror, or with the workspace it holds,
            // by the folder's own name where that is safe. The listing and the
            // link are off the main thread for the reason every other disk
            // read here is: `MainThreadWatch` installs a policy that logs one,
            // and `folderOpenTarget` stats each candidate.
            if (navigate) {
                val target = withContext(Dispatchers.IO) {
                    val opened = folderOpenTarget(
                        mirrorDir.absolutePath,
                        mirrorDir.list()?.asList().orEmpty(),
                    )
                    if (opened != mirrorDir.absolutePath) {
                        Logger.i(tag, "The granted folder holds a workspace; opening that")
                    }
                    // A name the provider did not answer makes no link. The copy's own,
                    // where it has exactly one, keeps the page on the path its editors,
                    // unsaved changes and terminals are under; a new link named for the
                    // fallback moved it to a path with none of them. With none, or
                    // several, there is no link to take and the copy opens by its hash.
                    val namedPath = if (answeredName != null) {
                        safManager.namedPathFor(mirrorDir, answeredName)
                    } else {
                        safManager.existingNamedPath(mirrorDir)
                    }
                    deviceFolderTarget(
                        target = opened,
                        mirrorPath = mirrorDir.absolutePath,
                        namedPath = namedPath?.path,
                        byName = copyIsNew || safManager.wasShownByName(mirrorDir),
                        openNow = openWorkspaceFolder,
                    )
                }
                // Readiness rather than the port, for the reason recreateWebView
                // gives: a device-folder sync can run for minutes, and a server
                // that died during it leaves `serverPort` set with nothing behind
                // it, so navigating puts a connection-refused page in front of the
                // folder the user just picked.
                //
                // But the folder is NOT dropped for it, which is the mistake the
                // first version of this guard made. The grant is taken and the
                // mirror is copied by the time we are here, so refusing to
                // navigate throws away minutes of the user's copy and says
                // nothing at all -- worse than the error page it avoids, because
                // an error page can at least be read. It is remembered instead
                // and the server is asked back: retryServerStart puts up the
                // loading page, whose URL names no folder, so onServerReady's
                // loadVSCode falls through the chain to this one.
                //
                // A port of 0 is a screen that has not yet seen this process's
                // server answer, which is where a folder picked across a
                // process restart lands: the picker's result comes in before
                // the binding does. No editor has been loaded on it yet, and the
                // first load reads the remembered folder, so remembering is all
                // it needs; gating the whole branch on the port let the editor
                // open the folder remembered before this one.
                if (serverPort > 0 && nodeService?.isServerReady() == true) {
                    navigateToFolder(serverPort, target)
                } else {
                    Logger.i(tag, "The server is not serving; opening the folder once it is")
                    if (!rememberWorkspaceFolder(target)) return@launch
                    if (serverPort > 0) retryServerStart()
                }
            }
        } catch (e: CancellationException) {
            // Not a folder that failed. This Activity is being destroyed and
            // took its scope with it, and the handlers below would read that
            // as a failed sync, `kotlinx.coroutines.CancellationException`
            // is a `java.util.concurrent` one, which is a plain `Exception`,
            // so it lands in the catch-all and every non-suspending statement
            // in it runs.
            //
            // What that costs is not a spurious toast. `safManager` belongs
            // to this Activity and `onDestroy` has already stopped its
            // watcher and unbound everything; [restoreWatcherAfterFailure]
            // would then start a FileObserver and the `saf-writeback` thread
            // again on that same engine, and only `stopWatching()` on that
            // instance can ever stop them. The replacement Activity builds
            // its own manager, so its `stopFileWatcher()` does not reach the
            // old one. Two engines end up watching one mirror, and the
            // orphaned one reads the `.partial` renames of the next sync as
            // the user's own edits and pushes them onto the user's documents
            // which is the exact damage the stop before the sync exists to
            // prevent.
            //
            // `SafSyncEngine.shutdown()`, which is what `onDestroy` calls, now
            // refuses that restart at the engine, so this is no longer the only
            // thing standing between a cancelled scope and an unstoppable
            // watcher. It stays because the rest of the handler is still wrong
            // for a cancellation: a toast and a "sync failed" dialog for an
            // Activity that is simply going away.
            //
            // Guarded because the window may already be gone: dismissing a
            // dialog whose Activity has been destroyed throws, and an
            // exception raised inside this handler would reach the scope's
            // handler rather than being the cancellation it is. Destroyed
            // alone: a screen that is only finishing still has its window,
            // and a dialog left on it is logged as a leaked window when the
            // screen goes.
            if (!isDestroyed) dialog.dismiss()
            throw e
        } catch (e: SecurityException) {
            // Guarded like the cancellation branch above, and for the reason
            // that one gives. A real failure can land here while this screen
            // is going away (a grant revoked mid-sync, a provider giving up
            // on a network share, in the moment the user swipes the app
            // away): dismissing a dialog whose window has already been torn
            // down throws, and a throw raised inside a catch leaves the
            // coroutine by the scope's uncaught handler rather than being
            // handled here at all, which is a crash on the way out of a
            // screen the user has already left. Skipping it leaves nothing on
            // screen, because the window that carried the dialog is what has
            // gone; the notice below is a toast and outlives it.
            if (!isDestroyed) dialog.dismiss()
            // The class and not the exception: its message quotes the tree URI, see [openSafFolder].
            Logger.e(tag, "SAF permission revoked during sync: ${e.javaClass.simpleName}")
            reportSyncFailure(
                getString(R.string.saf_sync_denied),
                restoreWatcherAfterFailure(previouslyWatched, uri)
            )
        } catch (e: FolderBusyException) {
            // Guarded for the reason the handler above it is.
            if (!isDestroyed) dialog.dismiss()
            // Refused before the mirror was touched, by a save, delete or rename
            // still on its way to the provider, or by another sync of the folder.
            // Nothing is half-written, so the watcher goes back even for the folder
            // that was refused: leaving it stopped kept write-back off for a folder
            // the user was still editing, and every page load then tried to adopt it
            // again and was refused again. The saves that follow wait behind the
            // write in flight; see [SafSyncEngine.outboundTurnWaitMs].
            Logger.w(tag, "The device folder is busy; leaving it as it was")
            // Its own resource: the exception's message is English and is for the log.
            reportSyncFailure(
                getString(R.string.saf_sync_busy),
                restoreWatcherAfterFailure(previouslyWatched, uri, mirrorUntouched = true)
            )
        } catch (e: Exception) {
            // Guarded for the reason the handler above it is.
            if (!isDestroyed) dialog.dismiss()
            // The class and not the exception: its message can name a path or the tree URI.
            Logger.e(tag, "SAF sync failed: ${e.javaClass.simpleName}")
            // Never null: many exceptions carry no message, and the sentence then
            // ended in the word null.
            reportSyncFailure(
                getString(R.string.saf_sync_failed, e.message ?: e.javaClass.simpleName),
                restoreWatcherAfterFailure(previouslyWatched, uri)
            )
        } finally {
            // Cleared however this ends, cancellation included: a folder left
            // marked as syncing would make every later page load into its
            // mirror a no-op, which is the defect this marker exists to avoid
            // rather than one to introduce.
            if (syncingFolder == uri) syncingFolder = null
            deviceFolderOpens.unlock()
        }
    }
}

/**
 * Puts the previous folder's watcher back after a failed sync, when doing so
 * is safe.
 *
 * Safe exactly when the folder that failed is not the folder that was being
 * watched, and that distinction is the whole function. Stopping the watcher
 * before the sync is what keeps the engine from observing its own writes,
 * but leaving it stopped is only justified for the folder the sync was
 * writing into: that mirror is part-written, and a watcher over a
 * part-written mirror would push that half onto the user's own documents
 * (damage to their only copy).
 *
 * A different folder's mirror was not touched at all. Leaving *its* watcher
 * dead buys nothing and costs the user the write-back for the folder still
 * on screen, which they go on editing in the belief it is being saved. That
 * is the failure this whole reorder exists to avoid, arriving from the other
 * direction. What a program changed in it while its watcher was stopped is
 * sent by the watcher put back here, which is what makes the notice's
 * "unaffected" true; see [SafSyncEngine.pauseWatching].
 *
 * Suspending for the reason [openSafFolder] hops: starting a watcher walks
 * the whole mirror and registers one kernel watch per directory, and this
 * runs from a failure handler with a dialog still on screen.
 *
 * @param mirrorUntouched the sync was refused before it wrote anything, so the
 *   previous folder's watcher goes back whichever folder failed.
 * @return whether write-back is running for the folder the user is looking at.
 */
internal suspend fun MainActivity.restoreWatcherAfterFailure(
    previous: Pair<File, Uri>?,
    failed: Uri,
    mirrorUntouched: Boolean = false,
): Boolean {
    val (mirrorDir, uri) = previous ?: return false
    if (!shouldRestorePreviousWatcher(uri.toString(), failed.toString(), mirrorUntouched)) {
        // Not put back, so what the stop began recording for it has no watcher to send it: ended here rather
        // than at the next stop, its next open reads those changes as made while the folder was closed.
        withContext(Dispatchers.IO) { safManager.dropStoppedFolderRecording() }
        return false
    }
    withContext(Dispatchers.IO) { safManager.startFileWatcher(mirrorDir, uri) }
    beginWatching(mirrorDir, uri)
    Logger.i(tag, "Restored the previous folder's watcher after a failed switch")
    return true
}

/** The manager has already retained process-wide ownership before watcher registration. */
internal fun MainActivity.beginWatching(mirrorDir: File, uri: Uri) {
    watchedSafFolder = mirrorDir to uri
}

/**
 * The local copies of device folders, as the JSON the storage screen renders.
 *
 * Runs on the bridge's disk worker (`AndroidBridge.listSafMirrors` hands it
 * there, so the page is not held while it runs) and walks every mirror to
 * size it. That is slow enough to be worth naming, and the alternative is
 * worse: a screen that offers a removal without saying what it costs is the
 * shape this whole feature exists to replace.
 */
internal fun MainActivity.deviceFolderCopiesAsJson(): String =
    JSONArray().apply {
        safManager.listMirrors().forEach { mirror ->
            put(JSONObject().apply {
                put("hash", mirror.hash)
                // Absent rather than filled in with the hash: the extension
                // decides how to name a folder the app can no longer name,
                // and a hash rendered as a folder name reads as a real one.
                if (mirror.displayName != null) put("name", mirror.displayName)
                put("bytes", mirror.bytes)
                put("lastOpened", mirror.lastOpened)
                put("granted", mirror.granted)
            })
        }
    }.toString()

/**
 * Removes a local copy after native confirmation, or says why it cannot be removed.
 *
 * Activity state provides specific in-use refusals. The manager checks shared
 * process usage again at the rename, even after a positive native answer. An
 * unconfirmed request is refused because snapshots cannot exclude later direct
 * filesystem writes.
 *
 * @return empty after removal or a user cancellation, otherwise a refusal reason.
 */
internal fun MainActivity.removeDeviceFolderCopy(hash: String, force: Boolean): String {
    val mirrorsRoot = Environment.getSafMirrorsDir(this)
    // The refusal is resolved here because this is the side that has a Context.
    // The predicate answers WHICH refusal applies and deliberately not what it
    // says, which is what keeps it pure and reachable from a JVM test with no
    // Activity. See SafStorageManager.RECLAIM_FOLDER_OPEN.
    val inUse = SafStorageManager.reclaimRefusal(
        hash = hash,
        watchedMirror = watchedSafFolder?.first?.name,
        syncingMirror = syncingFolder?.let { safManager.getMirrorDir(it).name },
        openWorkspaceMirror = SafStorageManager.mirrorNameFor(
            openWorkspaceFolder, mirrorsRoot
        ),
        watchedThisProcess = safManager.usedMirrorNames(),
    )?.let { getString(it) }
    if (inUse != null) return inUse

    // Asked of the user rather than taken on the caller's word; see
    // confirmForcedRemoval. A decline is answered with nothing to say, which
    // is the only honest answer this side has: the bundled extension draws
    // EVERY non-empty answer as "Could not remove that folder's local copy:
    // <this>", so any sentence at all reports a cancel the user chose as a
    // failure of the app's. It went out first as the not-a-copy sentence,
    // which told someone who had just pressed Cancel that the app had
    // overruled them to protect their data, and then as the retry sentence
    // the filesystem-refusal branch below owns, which invited them to press
    // the button they had just declined.
    //
    // The empty string is this method's success value, so a decline is
    // reported to the caller as ok. Nothing states or implies that anything
    // was freed on that road: the byte figure is announced from the success
    // branch below and from nowhere else, and the extension answers an ok by
    // reopening the folder list, where the copy the user kept is still on it.
    if (force && !confirmForcedRemoval()) return ""

    val freed = safManager.reclaimMirror(hash, force)
    return when (freed) {
        SafStorageManager.RECLAIM_UNKNOWN -> getString(R.string.saf_mirror_unknown)
        SafStorageManager.RECLAIM_REFUSED -> getString(R.string.saf_mirror_not_a_copy)
        SafStorageManager.RECLAIM_IN_USE -> getString(R.string.saf_mirror_folder_this_session)
        // The copy is still there and still whole: nothing was released and
        // nothing was deleted, so the sentence is the one that invites a retry.
        //
        // The sweep is started here as well, and that is what makes the retry
        // worth inviting. The rename fails because a `discarded-` directory an
        // earlier removal left behind is still sitting on the name this one
        // needs, so a retry fails the same way until something clears it, and
        // until now the only thing that did was the next launch. Sweeping now
        // takes the obstacle away while the user is still on the screen.
        SafStorageManager.RECLAIM_FAILED -> {
            thread(name = "saf-sweep", isDaemon = true) {
                try {
                    safManager.sweepDiscardedMirrors()
                } catch (e: Exception) {
                    Logger.w(tag, "Sweeping the removed copies failed: ${e.message}")
                }
            }
            getString(R.string.saf_mirror_not_removed)
        }
        else -> {
            // The mirror is already unreachable at this point; what is left is
            // the recursive delete, which takes as long as the tree is big.
            // Detached because nothing waits for it: the next launch pass
            // finishes any leftover, so the worst a killed process costs is
            // disk that is already spent.
            thread(name = "saf-sweep", isDaemon = true) {
                try {
                    safManager.sweepDiscardedMirrors()
                } catch (e: Exception) {
                    Logger.w(tag, "Sweeping the removed copies failed: ${e.message}")
                }
            }
            runOnUiThread {
                AppNotices.showNotice(
                    this,
                    getString(
                        R.string.saf_mirror_removed,
                        StorageManager.formatSize(freed, resources.configuration.locales[0]),
                    ),
                    Toast.LENGTH_LONG,
                )
            }
            ""
        }
    }
}

/**
 * Asks the user before deleting a copy that may contain files absent on the device.
 *
 * Any same-origin caller can set `force`, so the app obtains its own answer. The
 * dialog waits on the storage worker while the UI stays responsive. A positive
 * callback authorizes removal only when it arrives within the bounded wait.
 */
internal fun MainActivity.removalLatches(): MutableSet<CountDownLatch> =
    synchronized(this) {
        pendingRemovalLatches ?: java.util.concurrent.ConcurrentHashMap.newKeySet<CountDownLatch>().also {
            pendingRemovalLatches = it
        }
    }

internal fun MainActivity.confirmForcedRemoval(): Boolean {
    // The one thread this cannot be asked from, refused rather than obeyed.
    // Waiting for a dialog on the thread that has to draw it is a deadlock
    // until the bound expires, which the platform calls an ANR; answering no
    // is a removal that did not happen, which the caller already handles.
    // `BridgeCallbackThreadHopTest` is what keeps the real caller off this
    // thread, and this is here so a future one cannot make that mistake
    // silently.
    if (Looper.myLooper() == Looper.getMainLooper()) {
        Logger.e(tag, "A forced removal asked for confirmation on the UI thread; refusing")
        return false
    }
    val answered = CountDownLatch(1)
    val confirmed = AtomicBoolean(false)
    // The handle the timeout below needs. Written on the UI thread and read on
    // this one, so it is published rather than a plain field.
    val shown = AtomicReference<AlertDialog?>(null)
    val latches = removalLatches()
    latches.add(answered)
    try {
        if (destroyed) return false
        runOnUiThread {
            // A dialog on a window that is going away throws, and this is reached
            // from a thread that knows nothing about the screen's lifecycle. The
            // latch is released either way, so a removal is never left waiting out
            // the whole bound for a dialog that was never drawn.
            if (isFinishing || isDestroyed || destroyed) {
                answered.countDown()
                return@runOnUiThread
            }
            shown.set(
                trackDialog(
                    AlertDialog.Builder(this)
                        .setMessage(getString(R.string.saf_mirror_not_a_copy))
                        .setPositiveButton(R.string.saf_mirror_remove) { _, _ ->
                            confirmed.set(true)
                            answered.countDown()
                        }
                        .setNegativeButton(android.R.string.cancel) { _, _ -> answered.countDown() }
                        // Back and a tap outside both land here rather than on either
                        // button, and neither is a yes.
                        .setOnCancelListener { answered.countDown() }
                        .show()
                ) { answered.countDown() }
            )
        }
        val got = answered.await(FORCED_REMOVAL_CONFIRM_MS, TimeUnit.MILLISECONDS)
        if (!got) {
            // Taken down only when nobody answered: a button already dismissed its
            // own dialog, and dismiss() is not cancel(), so this cannot release the
            // latch a second time. Guarded like every other dialog call here,
            // because the window may have gone while the wait ran; destroyed
            // alone, because a screen that is only finishing still has it.
            runOnUiThread {
                if (!isDestroyed) shown.get()?.dismiss()
            }
        }
        return got && confirmed.get()
    } finally {
        latches.remove(answered)
    }
}

/**
 * Says that a folder did not open, and what that costs the user.
 *
 * The consequence is spelled out rather than left to be discovered. An
 * editor that has quietly stopped writing changes back is exactly the
 * failure that costs somebody an afternoon of work they believed was saved,
 * and a bare "failed to open" gives them no reason to suspect it.
 *
 * It is also stated only when it is true. Telling a user their work is not
 * saving when it is has its own cost, and after a failed switch away from a
 * healthy folder that folder is still being watched.
 *
 * Each case is one resource holding the whole sentence, with [reason] as its
 * argument, rather than a consequence appended to a cause in Kotlin. A
 * concatenation puts the cause first in every language and no translation can
 * move it.
 */
internal fun MainActivity.reportSyncFailure(reason: String, writeBackStillRunning: Boolean) {
    val message = if (writeBackStillRunning) {
        getString(R.string.saf_sync_failed_other_folder_watched, reason)
    } else {
        getString(R.string.saf_sync_failed_no_write_back, reason)
    }
    AppNotices.showNotice(this, message, Toast.LENGTH_LONG)
}

/**
 * Opens a previously selected SAF folder from the recent list.
 * Called from [AndroidBridge.openRecentFolder] via JS bridge.
 *
 * A URI this app holds no grant for is refused and nothing else happens. It
 * used to open the folder picker instead, and that made the system's
 * document-tree chooser something any caller could put on the user's screen
 * without ever asking for it: `openRecentFolder` is one of the relay commands,
 * the URI travels with the call, and the recent list the legitimate caller
 * chooses from is pruned of revoked grants before it is handed over
 * (`SafStorageManager.getPersistedFolders`), so a URI arriving here without a
 * grant is either a lapse in the moment between the listing and the tap or a
 * URI nobody was ever offered. Neither is a reason to open a chooser, and
 * picking a folder in one persists a grant and copies the whole tree in.
 *
 * The notice stays, because the first of those two really happens and the user
 * is owed the reason; the instruction in it already points at the command that
 * opens the picker deliberately.
 */
fun MainActivity.openRecentSafFolder(uri: Uri) {
    if (!safManager.hasPersistedPermission(uri)) {
        AppNotices.showNotice(
            this,
            getString(R.string.saf_permission_expired),
            Toast.LENGTH_LONG
        )
        return
    }
    handleSafFolderSelected(uri)
}

/** The maximum lifetime of a native removal confirmation, independent of RPC timing. */
internal const val FORCED_REMOVAL_CONFIRM_MS = 45_000L
