package com.vscodroid.storage

import android.content.Context
import android.net.Uri
import android.os.FileObserver
import android.provider.DocumentsContract
import com.vscodroid.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/**
 * Bidirectional sync engine between SAF content:// URIs and local mirror directories.
 *
 * ## Initial Sync (SAF → local)
 * Recursively walks the SAF document tree via [DocumentsContract] and copies
 * all files to a local mirror directory, preserving the folder structure.
 *
 * ## Write-back (local → SAF)
 * Watches the mirror with one [FileObserver] per directory. When a file is modified,
 * created, or deleted locally (by VS Code), the change is synced back to the original
 * SAF location via [ContentResolver]. One observer over the root is not enough: inotify
 * watches a directory rather than a tree, so a saved file one level down would update
 * the mirror and never reach the device.
 *
 * ## Conflict Resolution
 * Local changes win when both sides changed: the mirror is replaced only when the
 * source is newer, carries no timestamp, or matches in time while differing in size.
 * External changes are picked up the next time the folder is opened, which is
 * the only refresh that exists — there is no "Refresh from device" action, and
 * nothing calls [com.vscodroid.util.StorageManager.clearSafMirrors] either, so a
 * mirror cannot be cleared from inside the app. Reopening also removes mirror files
 * for documents deleted on the device, under the conditions [reconcileDeletions]
 * spells out.
 */
class SafSyncEngine(private val context: Context) {

    private val tag = "SafSyncEngine"
    private val writeBackQueue = ConcurrentLinkedQueue<SyncJob>()
    private var writeBackThread: Thread? = null
    @Volatile private var isWatching = false

    /**
     * One observer per watched directory, keyed by that directory.
     *
     * Holding them here is not only bookkeeping: [FileObserver]'s shared ObserverThread
     * refers to each observer through a [java.lang.ref.WeakReference], so one that
     * nothing else references stops delivering events as soon as it is collected.
     */
    private val watchers = mutableMapOf<File, DirectoryObserver>()
    private val watchersLock = Any()

    /**
     * Cache: relativePath → document ID. Built during [initialSync] and used for
     * O(1) write-back lookups instead of walking the tree for each event.
     *
     * Four threads reach it: [initialSync] on Dispatchers.IO, [resolveDocumentUri] from
     * an observer thread, [createInSaf] from the write-back thread, and [stopWatching]
     * from whichever thread closes the folder. A plain map rehashing under a concurrent
     * read loses entries or spins, so the map itself carries the synchronization.
     */
    private val docIdCache = ConcurrentHashMap<String, String>()

    /**
     * Mirror paths the device holds a document for that this sync never read.
     *
     * A document skipped for size, or one whose copy failed, leaves no trace in
     * the mirror, so "absent from the mirror" means both "not on the device" and
     * "on the device with content this app has never seen". Every write-back path
     * resolves that ambiguity the destructive way, by design: `createOneInSaf`
     * exists to write into an existing document rather than fork it, so a local
     * file appearing at that name opens the device's document with truncation and
     * a 60 MB archive becomes whatever was just typed.
     *
     * Absolute paths, like the upload journal's entries and for the same reason:
     * one engine serves every folder in turn.
     */
    private val unfetched = ConcurrentHashMap.newKeySet<String>()

    /**
     * The tree [docIdCache]'s entries were resolved against. The cache is cleared
     * and refilled per folder, and a write-back drain can outlive its folder, so
     * anything resolving at processing time has to know whether the cache still
     * speaks for the tree the job belongs to before it reads an answer from it.
     */
    @Volatile
    private var cacheTree: Uri? = null

    /**
     * Directories that have just left the mirror and may be the first half of a rename.
     *
     * inotify reports a rename as MOVED_FROM on the old name and MOVED_TO on the new one
     * and pairs the two with a cookie, which [FileObserver] does not expose, so the
     * halves arrive here as an unrelated delete and create. This is what carries the
     * first half across to the second: [renameSourceFor] decides whether a MOVED_TO may
     * claim one, and a claim turns the create into a rename of the device's own document.
     *
     * Entries expire on their own ([RENAME_PAIR_WINDOW_MS]); nothing acts when one does.
     * An unclaimed entry therefore costs the behaviour that would have happened anyway.
     */
    private val vanishedDirectories = mutableListOf<VanishedDirectory>()
    private val vanishedLock = Any()

    // -- Initial Sync --

    /**
     * Performs a full sync from SAF tree URI to the local mirror directory.
     *
     * @param safUri The tree URI granted by the SAF folder picker.
     * @param mirrorDir The local directory to mirror into.
     * @param onProgress Callback with (filesDone, totalFiles).
     */
    suspend fun initialSync(
        safUri: Uri,
        mirrorDir: File,
        onProgress: (Int, Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        Logger.i(tag, "Starting initial sync: $safUri → ${mirrorDir.absolutePath}")
        val startTime = System.currentTimeMillis()

        // Clear cache for fresh sync
        docIdCache.clear()
        // Scoped rather than cleared: this engine serves one folder at a time, and
        // a switch that fails partway must not drop the protection the previous
        // folder's mirror is still relying on.
        unfetched.removeAll { it.startsWith(mirrorDir.absolutePath + File.separator) }
        cacheTree = safUri

        // Phase 1: Enumerate all documents in the tree
        val documents = mutableListOf<DocumentInfo>()
        val rootDocId = DocumentsContract.getTreeDocumentId(safUri)
        docIdCache[""] = rootDocId  // root entry
        val enumerationComplete = walkTree(safUri, rootDocId, "", documents)

        val totalFiles = documents.count { !it.isDirectory }
        var filesDone = 0
        var skippedLarge = 0
        var failedCopies = 0
        var keptLocal = 0
        /*
         * What the mirror holds, for the files this sync can vouch for. Filled at the two
         * points below that know the mirror copy corresponds to the device document — it
         * was just written, or it was found identical — and nowhere else.
         *
         * An allowlist, and that is the whole point. The previous version recorded every
         * enumerated path and then subtracted the ways that could be wrong, which meant a
         * way nobody had thought of defaulted to "safe to delete". Four were found that
         * way, each a separate patch. Here a branch that does not record leaves its path
         * out, so an unforeseen one defaults to "never a candidate".
         */
        val recorded = mutableListOf<String>()

        Logger.i(tag, "Enumerated ${documents.size} items ($totalFiles files)")

        // Loaded once, before any doc is considered: the uploads a previous
        // session started and nothing finished. Their documents' timestamps are
        // this app's own interrupted writes, and phase 2 below has to know that
        // before it asks the clock anything.
        val unfinishedUploads = uploadsInFlight().toMutableSet()

        // Phase 2: Create directories and copy files
        for (doc in documents) {
            val localPath = File(mirrorDir, doc.relativePath)

            if (doc.isDirectory) {
                localPath.mkdirs()
            } else {
                // The one thing no timestamp comparison can see: this app was
                // writing this path back when it stopped, so a newer, shorter
                // document here is this app's own truncated copy rather than a
                // device edit. The mirror is kept, it is the only complete
                // version, and the upload attempted again, which is also the
                // repair. The record is consumed by the sync that acted on it;
                // the re-upload re-marks it if it is cut short again.
                //
                // Repaired here rather than queued: the queue belongs to the
                // watcher's lifecycle, and [startWatching] opens by calling
                // [stopWatching], whose no-worker branch clears the queue, so a
                // job offered between the sync and the thread that would run it
                // is discarded before anything executes it. The sync already
                // owns this moment; it is on the IO dispatcher and mid-copy.
                if (localPath.exists() && localPath.absolutePath in unfinishedUploads) {
                    unfinishedUploads.remove(localPath.absolutePath)
                    clearUploadInFlight(localPath.absolutePath)
                    keptLocal++
                    Logger.i(
                        tag,
                        "Kept the mirror of ${doc.relativePath}: its device copy was an " +
                            "upload this app did not finish"
                    )
                    writeLocalToSaf(localPath, doc.uri)
                    filesDone++
                    onProgress(filesDone, totalFiles)
                    continue
                }
                // Skip files larger than MAX_FILE_SIZE.
                // Not recorded, because this sync did not fetch the file and so cannot
                // say what is at that path. Usually there is nothing; but a file that was
                // under the limit at an earlier sync and has since grown past it leaves a
                // real, sync-written copy sitting there, and "nothing ever wrote this"
                // would be the wrong reason for the right behaviour. Not vouching for it
                // costs a stale copy that lingers, which is the direction to fail in.
                if (doc.size > MAX_FILE_SIZE) {
                    skippedLarge++
                    unfetched.add(localPath.absolutePath)
                    Logger.d(tag, "Skipped large file: ${doc.relativePath} (${doc.size / 1_048_576}MB)")
                    filesDone++
                    onProgress(filesDone, totalFiles)
                    continue
                }
                if (!shouldOverwriteMirror(
                        localPath.exists(), localPath.lastModified(), localPath.length(),
                        doc.lastModified, doc.size
                    )
                ) {
                    keptLocal++
                    // Inside this branch the provider reported a time (an unknown one
                    // copies) and the mirror is not older, so equal means the mirror is
                    // already this document and can be vouched for, while strictly newer
                    // means it holds an edit that has not been written back and cannot.
                    if (localPath.lastModified() == doc.lastModified) {
                        recordIdentity(recorded, doc.relativePath, localPath)
                    }
                    unfetched.remove(localPath.absolutePath)
                    Logger.d(tag, "Kept local copy: ${doc.relativePath}")
                    filesDone++
                    onProgress(filesDone, totalFiles)
                    continue
                }
                localPath.parentFile?.mkdirs()
                if (copyDocumentToLocal(doc.uri, localPath, doc.lastModified)) {
                    recordIdentity(recorded, doc.relativePath, localPath)
                    unfetched.remove(localPath.absolutePath)
                } else {
                    // The mirror has no copy and the device does. Counted as well
                    // as recorded: a failed copy had no counter at all, so the
                    // summary reported a clean sync for a folder it did not read.
                    failedCopies++
                    unfetched.add(localPath.absolutePath)
                }
                filesDone++
                onProgress(filesDone, totalFiles)
            }
        }

        // Phase 3: drop what the device no longer has
        val removed = reconcileDeletions(mirrorDir, documents, enumerationComplete, recorded)

        val elapsed = System.currentTimeMillis() - startTime
        Logger.i(
            tag,
            "Initial sync complete: $filesDone files ($skippedLarge too large, " +
                "$failedCopies could not be copied, $keptLocal kept, " +
                "${recorded.size} vouched for, $removed removed) in ${elapsed}ms"
        )
    }

    /**
     * Removes mirror files for documents that have since been deleted on the device.
     *
     * Reopening a folder used to only create and overwrite, so anything deleted on the
     * device came straight back from the stale mirror — and because a MODIFY on a file
     * the tree no longer knows falls through to a create, editing it pushed it back
     * onto the device too.
     *
     * The two ways of being wrong here do not cost the same. A file left behind is
     * untidy; deleting work that exists only in the mirror cannot be undone. So this
     * removes only what a *complete* enumeration proves is gone, and only files it can
     * show it put there itself:
     *
     * - A partial enumeration proves nothing. [walkTree] logs and carries on when a
     *   provider query fails, so a folder that answered for two directories out of
     *   twenty would otherwise read as "eighteen directories were deleted". One failure
     *   anywhere disables the pass and leaves the last complete record untouched.
     * - Neither does an enumeration that succeeded and returned nothing. A folder the
     *   user emptied and a provider answering for a volume that is no longer mounted
     *   look identical from here, and only one of them costs the whole mirror. The
     *   price of declining is a folder emptied on the device keeping its mirror copies
     *   until they are deleted in the editor, which propagates the other way.
     * - **A candidate must still be the file that was recorded**, matched on modification
     *   time *and* length, not merely on its path. This is the rule the others used to
     *   stand in for, and getting here took four separate defects to notice. Each was a
     *   different way for a path to be recorded while the mirror held something else — a
     *   local edit, a file too large to have ever been copied, a copy that failed — and
     *   each was patched on its own. They were one gap: the record named *paths*, while
     *   the argument for deleting needs *identity*. Matching identity is what makes a
     *   fifth way, which nobody has found yet, stop mattering: a path recorded in error
     *   will not have a file that matches what was recorded for it.
     * - A line that cannot be parsed that way is never a candidate, and neither is any
     *   line of a record that does not open with [RECORD_HEADER]. Records written by an
     *   earlier build are one path per line and carry no identity at all, so they are
     *   unverifiable by construction — and unverifiable has to mean "keep". The header is
     *   what extends that to a format this build has never seen: field count is not a
     *   version, and the operation on the other side of the guess is a delete.
     * - A candidate that does not resolve inside the mirror is left alone. The record is
     *   built from provider-supplied display names, and a delete is the wrong operation
     *   to point at a path that walked out of the directory it belongs to.
     * - Directories are left alone. Removing one means a recursive delete, the
     *   operation with the worst outcome if any of the reasoning above is wrong. An
     *   empty directory left behind is noise.
     *
     * **Where all of this stops being true.** Every line of it assumes the provider
     * reports `COLUMN_LAST_MODIFIED`. When it does not, [shouldOverwriteMirror]'s
     * unknown-time branch returns true on *every* comparison, so an edit that has not been
     * written back is overwritten on the next reopen — before deletion is considered at
     * all. Nothing recorded here can protect what the copy already replaced, and no
     * identity check reaches a file that is gone. The account above of four defects
     * turning out to be one gap holds for providers that report a time, and only those.
     * MTP, some USB-OTG bridges and some network providers do not.
     *
     * @return how many files were removed.
     */
    private fun reconcileDeletions(
        mirrorDir: File,
        documents: List<DocumentInfo>,
        enumerationComplete: Boolean,
        recorded: List<String>
    ): Int {
        if (!enumerationComplete || documents.isEmpty()) {
            Logger.w(
                tag,
                "Enumeration proved nothing — leaving the mirror of ${mirrorDir.name} as it is"
            )
            return 0
        }

        val record = File(mirrorDir.path + SYNCED_RECORD_SUFFIX)
        val present = documents.filterNot { it.isDirectory }.map { it.relativePath }.toSet()
        var removed = 0

        // Resolved rather than checked lexically: a mirror is routinely a checked-out
        // repository, so a link inside one is attacker-supplied in the ordinary case and
        // `..` handling alone would follow it out.
        val confine = try {
            mirrorDir.canonicalPath + File.separator
        } catch (e: Exception) {
            Logger.w(tag, "Could not resolve the mirror's own path; removing nothing: ${e.message}")
            return 0
        }

        val lines = readSyncedRecord(record)
        val entries = if (lines.firstOrNull() == RECORD_HEADER) {
            lines.drop(1)
        } else {
            if (lines.isNotEmpty()) {
                Logger.i(tag, "Record of ${mirrorDir.name} is not this format; removing nothing")
            }
            emptyList()
        }

        for (line in entries) {
            val parts = line.split('\t')
            if (parts.size != 3) continue  // an older build's record, or a damaged line
            val path = parts[0]
            val wasModified = parts[1].toLongOrNull() ?: continue
            val wasSize = parts[2].toLongOrNull() ?: continue

            if (path.isEmpty() || path in present) continue
            val stale = File(mirrorDir, path)
            if (!stale.isFile) continue
            val resolved = try {
                stale.canonicalPath
            } catch (e: Exception) {
                continue
            }
            if (!resolved.startsWith(confine)) {
                Logger.w(tag, "Recorded path resolves outside the mirror, not touching it: $path")
                continue
            }
            if (stale.lastModified() != wasModified || stale.length() != wasSize) {
                Logger.d(tag, "Kept a file that is no longer the copy recorded for it: $path")
                continue
            }
            if (stale.delete()) removed++
        }

        try {
            record.writeText((listOf(RECORD_HEADER) + recorded).joinToString("\n"))
        } catch (e: Exception) {
            Logger.w(tag, "Could not record the synced set: ${e.message}")
        }
        return removed
    }

    /**
     * Appends [path]'s identity as it stands on disk, or nothing when there is no file
     * there.
     *
     * Read back rather than predicted, so that a timestamp the filesystem truncated or a
     * length the provider misreported cannot put the record out of step with the disk.
     *
     * ⚠️ Reading back is only sound because the caller has established the file is this
     * sync's. It cannot tell on its own: [copyDocumentToLocal] writes beside the
     * destination and renames, deliberately leaving the destination untouched when it
     * fails — so after a failed copy this reads whatever was already there, which can be
     * an edit of the user's that no sync ever wrote. Recording that identity is what
     * would make the user's only copy match, and match is what licenses the delete. Call
     * this only where the write is known to have landed.
     */
    private fun recordIdentity(into: MutableList<String>, path: String, file: File) {
        if (!file.isFile) return
        // A tab or a line break in a provider's display name would split into a line that
        // parses as a different file. Such a path simply never becomes a candidate. `\r`
        // counts: readLines ends a line on it as readily as on `\n`, and both are legal
        // in a filename.
        if (path.any { it == '\t' || it == '\n' || it == '\r' }) return
        into.add(identityLine(path, file))
    }

    /**
     * One file's line in the synced record: relative path, mtime, size, tab separated.
     *
     * Shared rather than spelled out at each site, because two of them have to agree
     * byte for byte or the disagreement shows up as behaviour: [recordIdentity] writes
     * the line and [holdsOnlyVouchedCopies] looks the same line up. A fixture that
     * composes it by hand is a third reader of the same bytes, which is why the tests
     * call this too.
     */
    internal fun identityLine(path: String, file: File): String =
        "$path\t${file.lastModified()}\t${file.length()}"

    /**
     * The lines of the last complete sync's record, or none when there is no usable one.
     *
     * Each is `path`, `modification time` and `length` separated by tabs. Parsing is the
     * caller's, because a line it cannot read has to be dropped rather than repaired.
     */
    /**
     * Whether every file under [mirrorDir] is one this app can prove it copied from the
     * device folder, unchanged since.
     *
     * The question the reclaim pass has to answer before deleting a mirror, and it is
     * narrower than the one it used to ask. That pass reads [uploadsInFlight], which
     * records write-backs that were ATTEMPTED and failed. A file that was never queued
     * for write-back at all has no journal entry, and there are several ways to be in
     * that state: anything under [SKIP_DIRECTORIES], so a `.git` from a terminal clone;
     * a file below a directory past [MAX_WATCHED_DIRECTORIES]; anything written while no
     * watcher was running; a mirror copy [initialSync] kept for being newer than the
     * device document, which it never uploads; entries past the upload cap; and jobs the
     * queue dropped on [stopWatching]. Each of those is the user's only copy, and the
     * journal cannot see any of them.
     *
     * So the test is inverted: rather than look for evidence a file is precious, require
     * evidence that it is disposable. The record beside the mirror is that evidence and
     * already exists. [recordIdentity] writes one line per file the device enumeration
     * produced, and this compares against it in the same format, so a file absent from
     * the record, or present but no longer the bytes recorded for it, means the mirror is
     * not purely a copy.
     *
     * Fails closed, in three directions, all deliberate. A missing record, a record whose
     * first line is not [RECORD_HEADER], and an unreadable directory each return false
     * and keep the mirror. That is the same direction [reconcileDeletions] takes when it
     * cannot vouch for a path, and the cost of being wrong here is disk rather than the
     * user's only copy.
     *
     * Symlinks are not followed: `walkTopDown` reports the link itself, and [isLink]
     * sends it down the not-a-plain-file branch, so a link out of the mirror can neither
     * be vouched for nor be walked through.
     */
    internal fun holdsOnlyVouchedCopies(mirrorDir: File): Boolean {
        val lines = readSyncedRecord(File(mirrorDir.path + SYNCED_RECORD_SUFFIX))
        if (lines.firstOrNull() != RECORD_HEADER) return false
        val vouched = lines.drop(1).toHashSet()
        // A directory the walk could not read leaves the mirror only partly examined,
        // which is indistinguishable from an empty one to `all`. Recorded rather than
        // returned from the handler: `onFail` is not inline, so it cannot return here.
        var unreadable = false
        return try {
            mirrorDir.walkTopDown().onFail { _, _ -> unreadable = true }.all { file ->
                if (isLink(file)) false
                else if (!file.isFile) true
                else identityLine(file.toRelativeString(mirrorDir), file) in vouched
            } && !unreadable
        } catch (e: Exception) {
            Logger.w(tag, "Could not walk ${mirrorDir.name}; treating it as unvouched: ${e.message}")
            false
        }
    }

    private fun readSyncedRecord(record: File): List<String> =
        if (!record.isFile) {
            emptyList()
        } else {
            try {
                record.readLines()
            } catch (e: Exception) {
                Logger.w(tag, "Could not read the synced record: ${e.message}")
                emptyList()
            }
        }

    // -- File Watching (Write-back) --

    /**
     * Starts watching the mirror directory for changes and syncing them back to SAF.
     * Must be called after [initialSync] completes.
     */
    fun startWatching(mirrorDir: File, safUri: Uri) {
        stopWatching()

        isWatching = true
        watchTree(mirrorDir, mirrorDir, safUri)

        // Background thread to process write-back queue
        writeBackThread = thread(name = "saf-writeback", isDaemon = true) {
            runWriteBackLoop { isWatching }
        }

        val watched = synchronized(watchersLock) { watchers.size }
        Logger.i(tag, "File watcher started for ${mirrorDir.absolutePath} ($watched directories)")
    }

    /**
     * Processes queued write-backs until [isRunning] goes false or the thread is
     * interrupted, then sends out whatever is still queued.
     *
     * Takes its termination condition as a parameter so a test can drive the loop
     * without a live watcher; the caller passes the watcher's own flag.
     */
    internal fun runWriteBackLoop(isRunning: () -> Boolean) {
        var interrupted = false
        try {
            while (isRunning()) {
                val job = writeBackQueue.poll()
                if (job != null) {
                    processWriteBack(job)
                } else {
                    Thread.sleep(WRITEBACK_POLL_MS)
                }
            }
        } catch (_: InterruptedException) {
            // stopWatching() interrupts to wake this thread out of the sleep it spends
            // nearly all of its idle time in, so this is the ordinary way the loop ends,
            // not a fault. Letting it escape reaches the thread's uncaught handler, and
            // Android's default handler ends the process — closing a folder, which
            // happens on every folder switch and on destroy, would close the app.
            interrupted = true
        }
        // Whichever way the loop ended, the queue can still hold writes the user is
        // expecting on the device, and they have to go out with the interrupt status
        // clear: the stream copies below throw InterruptedIOException on a thread still
        // carrying the flag, losing exactly what this drain exists to save.
        if (Thread.interrupted()) interrupted = true

        var remaining = writeBackQueue.poll()
        while (remaining != null) {
            processWriteBack(remaining)
            remaining = writeBackQueue.poll()
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    /**
     * Stops the file watcher and drains the write-back queue.
     */
    fun stopWatching() {
        isWatching = false
        synchronized(watchersLock) {
            watchers.values.forEach { it.stopWatching() }
            watchers.clear()
        }
        // Wake the thread out of its sleep, then wait for it to drain remaining writes.
        // An idle queue drains in microseconds, so this costs only what there is to lose.
        val worker = writeBackThread
        writeBackThread = null
        worker?.interrupt()
        try { worker?.join(DRAIN_GRACE_MS) } catch (_: InterruptedException) {}

        if (worker != null && worker.isAlive) {
            // Still draining. Emptying the queue from here would throw away exactly the
            // writes the drain exists to save, and would do it in the case where there
            // are the most of them — a burst of saves, or one slow provider. The thread
            // owns the queue until it finishes; jobs carry the URIs they belong to, so a
            // later lifecycle sharing the queue cannot misdirect them.
            Logger.w(tag, "Write-back still draining after ${DRAIN_GRACE_MS}ms; leaving it to finish")
        } else {
            writeBackQueue.clear()
        }
        // docIdCache is deliberately not cleared here. A drain that outlived the wait
        // still needs the mappings of the folder it is finishing, and [initialSync]
        // clears the cache itself before anything reads it for the next one.
        //
        // The half-renames are cleared, and the difference is that nothing consumes them
        // after this point: a MOVED_TO of the next folder must not be able to claim a
        // directory that left the previous one, which would rename a document in a folder
        // the user has closed.
        synchronized(vanishedLock) { vanishedDirectories.clear() }
        Logger.i(tag, "File watcher stopped")
    }

    // -- Internal: Watch Registration --

    /**
     * Puts an observer on each of [watchableDirectories] for [dir], one per directory,
     * because a watch descriptor covers a directory and not a tree.
     */
    private fun watchTree(dir: File, rootDir: File, safTreeUri: Uri) {
        // The walk calls listFiles() once per directory, so it stays outside the lock:
        // holding a monitor across a tree's worth of filesystem calls would block the
        // observer thread's own registrations behind them. Its result is a bound on how
        // much work follows, not a reservation — the cap is enforced again below, where
        // it can be exact.
        val room = synchronized(watchersLock) { MAX_WATCHED_DIRECTORIES - watchers.size }
        val targets = watchableDirectories(dir, room)
        val started = mutableListOf<DirectoryObserver>()
        var atLimit = false

        synchronized(watchersLock) {
            for (target in targets) {
                if (watchers.size >= MAX_WATCHED_DIRECTORIES) {
                    atLimit = true
                    break
                }
                if (watchers.containsKey(target)) continue
                val observer = DirectoryObserver(target, rootDir, safTreeUri)
                watchers[target] = observer
                started += observer
            }
        }

        if (atLimit || targets.size >= room) {
            Logger.w(
                tag,
                "Watch limit of $MAX_WATCHED_DIRECTORIES reached; directories below " +
                    "${dir.name} are unwatched"
            )
        }
        // Registering the watch reaches the kernel, and nothing here needs the lock
        // held while it does.
        started.forEach { it.startWatching() }
    }

    /** Releases the observer for [dir] and for anything below it. A no-op if unwatched. */
    private fun unwatchTree(dir: File) {
        synchronized(watchersLock) {
            // Every deleted file reaches here, and only a deleted directory can be
            // holding watches. Without this the scan below would run once per entry of
            // a `git clean`, across the whole map, on the observer thread.
            if (!watchers.containsKey(dir)) return

            val prefix = dir.path + File.separator
            watchers.keys
                .filter { it == dir || it.path.startsWith(prefix) }
                .forEach { watchers.remove(it)?.stopWatching() }
        }
    }

    // -- Internal: Tree Walking --

    /**
     * Recursively walks a SAF document tree, collecting [DocumentInfo] entries.
     *
     * @return whether every directory under [parentDocId] could be enumerated. A false
     *   here is what stops [reconcileDeletions] from reading a provider that stopped
     *   answering as a device folder that was emptied.
     */
    private fun walkTree(
        treeUri: Uri,
        parentDocId: String,
        parentRelPath: String,
        result: MutableList<DocumentInfo>
    ): Boolean {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        var complete = true

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                // Not every provider fills this one, so a missing column is not fatal.
                val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idIndex)
                    val name = cursor.getString(nameIndex)
                    val mimeType = cursor.getString(mimeIndex)
                    val size = cursor.getLong(sizeIndex)
                    val lastModified =
                        if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) cursor.getLong(modifiedIndex) else 0L
                    val isDir = mimeType == DocumentsContract.Document.MIME_TYPE_DIR

                    // Before the name is composed into a path, not after: see
                    // [isSafeSegment].
                    if (!isSafeSegment(name)) {
                        Logger.w(tag, "Skipped a document whose display name is not a path segment")
                        continue
                    }

                    val relativePath = if (parentRelPath.isEmpty()) name else "$parentRelPath/$name"
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

                    // Skip hidden files and common large directories
                    if (shouldSkip(name, isDir)) continue

                    // Cache the docId so write-back resolves this path without
                    // walking the tree again
                    docIdCache[relativePath] = docId

                    result.add(DocumentInfo(docUri, docId, relativePath, isDir, size, lastModified))

                    if (isDir && !walkTree(treeUri, docId, relativePath, result)) {
                        complete = false
                    }
                }
            } ?: return false  // the provider refused to answer at all
        } catch (e: Exception) {
            Logger.w(tag, "Failed to enumerate children of $parentDocId: ${e.message}")
            return false
        }
        return complete
    }

    /**
     * Skip patterns: large auto-generated directories that would slow sync unnecessarily.
     */
    private fun shouldSkip(name: String, isDir: Boolean): Boolean =
        Companion.shouldSkip(name, isDir)

    // -- Internal: File Operations --

    /**
     * Copies a single SAF document to a local file.
     *
     * @return whether [dest] now holds this document. False means [dest] was left exactly
     *   as it was — which, because of the partial-and-rename below, can mean it still
     *   holds an edit of the user's that no sync wrote. Callers that record what the
     *   mirror holds have to know the difference.
     */
    private fun copyDocumentToLocal(docUri: Uri, dest: File, sourceModified: Long): Boolean {
        // Written beside the destination and moved into place only once the stream
        // finished. Writing straight to dest would truncate it first, so a copy cut
        // short — by an exception, or by the process being killed mid-stream — would
        // leave a short file carrying a fresh timestamp. That is indistinguishable
        // from an unsaved local edit, and the sync decision has to tell them apart.
        val partial = File(dest.parentFile, "${dest.name}$PARTIAL_SUFFIX")
        try {
            val source = context.contentResolver.openInputStream(docUri)
            if (source == null) {
                Logger.w(tag, "No stream for ${docUri.lastPathSegment}")
                return false
            }
            source.use { input ->
                FileOutputStream(partial).use { output ->
                    input.copyTo(output, COPY_BUFFER_SIZE)
                }
            }
            // Stamp with the source's own time so later syncs compare two timestamps
            // from the same clock rather than a provider's against the filesystem's.
            if (sourceModified > 0) partial.setLastModified(sourceModified)
            if (!partial.renameTo(dest)) {
                partial.delete()
                Logger.w(tag, "Could not move ${partial.name} into place")
                return false
            }
            return true
        } catch (e: Exception) {
            partial.delete()
            Logger.w(tag, "Failed to copy ${docUri.lastPathSegment} → ${dest.name}: ${e.message}")
            return false
        }
    }

    /**
     * Writes a local file's contents back to its corresponding SAF document.
     *
     * The copy is bracketed by the in-flight journal, and the bracket is the whole
     * point of this method's shape: `"wt"` truncates the document at open, and the
     * provider stamps a fresh modification time on the shorter bytes, so a copy
     * that dies partway leaves the device holding a *newer* copy of a file whose
     * only complete version is the mirror's. That is indistinguishable, at the
     * next sync, from an edit made on the device, and the sync would copy the
     * truncation over the edit. The journal line is written before the stream
     * opens and removed only by a copy that ran to its end, so the next sync can
     * tell the two apart by the one fact it can know: whether this app was
     * mid-upload when the copy stopped.
     *
     * One retry, because a first attempt that fails has already paid the
     * truncation, and a second full write is cheaper than a session of a stale
     * device copy. Permission loss is not retried: nothing about a revoked grant
     * gets better within a second.
     */
    private fun writeLocalToSaf(localFile: File, safDocUri: Uri) {
        markUploadInFlight(localFile.absolutePath)
        var attempts = 0
        while (true) {
            try {
                val copied = context.contentResolver.openOutputStream(safDocUri, "wt")
                    ?.use { output ->
                        localFile.inputStream().use { input ->
                            input.copyTo(output, COPY_BUFFER_SIZE)
                        }
                        true
                    } ?: false
                if (copied) {
                    clearUploadInFlight(localFile.absolutePath)
                    return
                }
                attempts++
            } catch (e: SecurityException) {
                Logger.e(tag, "Permission revoked while writing back: ${localFile.name}")
                return
            } catch (e: Exception) {
                attempts++
            }
            if (attempts >= 2) {
                Logger.e(
                    tag,
                    "Write-back of ${localFile.name} did not finish; its device copy " +
                        "is recorded as not to be trusted"
                )
                return
            }
        }
    }

    private fun uploadJournal(): File = File(context.filesDir, UPLOADS_IN_FLIGHT_FILE)

    private fun markUploadInFlight(absolutePath: String) = synchronized(uploadJournalLock) {
        try {
            val journal = uploadJournal()
            val lines = if (journal.isFile) journal.readLines() else emptyList()
            if (absolutePath !in lines) {
                rewriteJournal(lines + absolutePath)
            }
        } catch (e: Exception) {
            Logger.w(tag, "Could not record the upload in flight: ${e.message}")
        }
    }

    private fun clearUploadInFlight(absolutePath: String) = synchronized(uploadJournalLock) {
        try {
            val journal = uploadJournal()
            if (journal.isFile) {
                val all = journal.readLines()
                val kept = all.filter { it != absolutePath }
                if (kept.size != all.size) rewriteJournal(kept)
            }
        } catch (e: Exception) {
            Logger.w(tag, "Could not clear the upload record: ${e.message}")
        }
    }

    /**
     * Drops every journal entry under [mirrorRoot], for when the mirror itself is
     * going away.
     *
     * An entry outlives its mirror otherwise, and the damage is delayed rather
     * than avoided: the folder's permission lapses, the mirror is reclaimed with
     * the entry still recorded, and a re-grant weeks later hashes back to the same
     * path. The first sync after it copies the device down with the mirror absent,
     * so the entry is not consumed; an edit made on the device after that is then
     * read back as this app's own interrupted upload and thrown away in favour of
     * the stale copy. Reclaiming the mirror has to reclaim its distrust with it.
     */
    internal fun clearUploadsUnder(mirrorRoot: File) {
        synchronized(uploadJournalLock) {
            try {
                val journal = uploadJournal()
                if (!journal.isFile) return
                val prefix = mirrorRoot.absolutePath + File.separator
                val all = journal.readLines()
                val kept = all.filterNot { it.startsWith(prefix) }
                if (kept.size != all.size) rewriteJournal(kept)
            } catch (e: Exception) {
                Logger.w(tag, "Could not clear the upload records under ${mirrorRoot.name}: ${e.message}")
            }
        }
    }

    internal fun uploadsInFlight(): Set<String> = synchronized(uploadJournalLock) {
        try {
            val journal = uploadJournal()
            if (journal.isFile) journal.readLines().filter { it.isNotBlank() }.toSet()
            else emptySet()
        } catch (e: Exception) {
            // Said rather than swallowed: an unreadable journal silently disables
            // the whole bracket, and that is worth a log line when it happens.
            Logger.w(tag, "Could not read the upload journal: ${e.message}")
            emptySet()
        }
    }

    /**
     * Replaces the journal in one step, or leaves the previous content exactly
     * where it was.
     *
     * Caller must hold [uploadJournalLock]. The rename is the atomic half; a
     * truncate-and-write here would lose every bracket at once to a process death
     * in the middle, which is the one failure this file exists to remember across.
     */
    private fun rewriteJournal(lines: List<String>) {
        val journal = uploadJournal()
        if (lines.isEmpty()) {
            journal.delete()
            return
        }
        val tmp = File(journal.parentFile, journal.name + PARTIAL_SUFFIX)
        tmp.writeText(lines.joinToString("\n"))
        if (!tmp.renameTo(journal)) {
            journal.writeText(lines.joinToString("\n"))
            tmp.delete()
        }
    }

    /**
     * Puts a locally created file into the SAF tree, writing into the document that
     * already carries that name rather than adding a second one.
     *
     * [DocumentsContract.createDocument] does not merge: handed a name the folder
     * already has, a provider invents "notes (1).txt" and the user's file quietly
     * forks. Two ordinary things arrive here as a create — the rename
     * [copyDocumentToLocal] performs to move a finished copy into place, and any local
     * rename over an existing name, which is what `mv` and a git checkout do. Resolving
     * first is also what makes the MODIFY-on-unknown fallback in [processWriteBack]
     * safe to keep.
     */
    private fun createInSaf(localFile: File, parentSafUri: Uri, treeUri: Uri) {
        val docUri = createOneInSaf(localFile, parentSafUri, treeUri) ?: return
        if (localFile.isDirectory) {
            // A directory that just appeared brings whatever is inside it; without
            // this it landed on the device empty and its files stayed in the mirror.
            // What it cannot do is stand in for a copy that already exists on the
            // device: it stops at [MAX_UPLOAD_ENTRIES] and excludes whatever
            // [SKIP_DIRECTORIES] kept out of the mirror. That is why a rename does not
            // come through here at all when [handleMirrorEvent] can pair its two halves
            // it renames the device's document instead, and why the old name is
            // never deleted when it cannot.
            createChildrenInSaf(localFile, docUri, treeUri)
        }
    }

    /**
     * Creates or opens the document for [localFile] under [parentSafUri], writing its
     * bytes if it is a file, and returns the document's URI.
     *
     * Does not descend. [createInSaf] owns that, so that the recursion lives in one
     * place and this stays the single-entry operation both it and
     * [createChildrenInSaf] need.
     */
    private fun createOneInSaf(localFile: File, parentSafUri: Uri, treeUri: Uri): Uri? {
        return try {
            val parentDocId = DocumentsContract.getDocumentId(parentSafUri)
            val existingDocId = findChildDocId(treeUri, parentDocId, localFile.name)

            val docUri = if (existingDocId != null) {
                DocumentsContract.buildDocumentUriUsingTree(treeUri, existingDocId)
            } else {
                val mimeType = if (localFile.isDirectory) {
                    DocumentsContract.Document.MIME_TYPE_DIR
                } else {
                    guessMimeType(localFile.name)
                }
                DocumentsContract.createDocument(
                    context.contentResolver, parentSafUri, mimeType, localFile.name
                )
            } ?: return null

            if (localFile.isFile) {
                writeLocalToSaf(localFile, docUri)
            }
            // Cache the document ID for future write-back lookups, but only when the
            // parent is one this cache knows. A miss is not "the parent is the root" --
            // initialSync puts the root in under the empty key, so the root is found.
            // It means the cache belongs to a different folder than this job does, which
            // is what a drain outliving its own lifecycle looks like. Writing the entry
            // anyway would file a document from the old folder under a plausible name in
            // the new one, and the next write-back for that name would land in the wrong
            // folder entirely.
            val parentRelPath = docIdCache.entries.firstOrNull { it.value == parentDocId }?.key
            if (parentRelPath != null) {
                val relPath = if (parentRelPath.isEmpty()) localFile.name
                    else "$parentRelPath/${localFile.name}"
                docIdCache[relPath] = DocumentsContract.getDocumentId(docUri)
            }
            docUri
        } catch (e: Exception) {
            Logger.w(tag, "Failed to create ${localFile.name} in SAF: ${e.message}")
            null
        }
    }

    /**
     * Creates everything under [localDir] on the device, beneath [dirSafUri].
     *
     * Iterative rather than recursive through [createInSaf], and that is deliberate:
     * [uploadableEntries] already returns parents before children, so each entry's
     * parent document exists by the time it is reached, and the URI of every
     * directory created along the way is remembered here. Recursing instead would
     * re-query the provider for a parent it had just made.
     *
     * A child whose parent is missing from [created] is skipped rather than guessed
     * at. That happens when the parent's own creation failed, and inventing a place
     * to put the child would file it somewhere the user did not put it.
     */
    private fun createChildrenInSaf(localDir: File, dirSafUri: Uri, treeUri: Uri) {
        val entries = uploadableEntries(localDir, MAX_UPLOAD_ENTRIES)
        if (entries.isEmpty()) return

        val created = mutableMapOf(localDir.absolutePath to dirSafUri)
        var made = 0
        for (entry in entries) {
            // Split rather than chained: `parentFile` is a platform type, so the
            // chained form indexed the map with a String? and the compiler warned
            // about it. An entry with no parent cannot be placed anywhere.
            val parentPath = entry.parentFile?.absolutePath ?: continue
            val parentUri = created[parentPath] ?: continue
            createOneInSaf(entry, parentUri, treeUri)?.let { uri ->
                if (entry.isDirectory) created[entry.absolutePath] = uri
                made++
            }
        }
        if (entries.size >= MAX_UPLOAD_ENTRIES) {
            // Said out loud rather than silently truncated: the user's copy on the
            // device is then genuinely incomplete, and a log line is the only place
            // that can say so.
            Logger.w(tag, "Stopped at $MAX_UPLOAD_ENTRIES entries under ${localDir.name}; " +
                "the rest were not copied to the device")
        }
        Logger.i(tag, "Created $made of ${entries.size} entries under ${localDir.name}")
    }

    /**
     * Looks up a direct child of [parentDocId] by display name, from the provider.
     *
     * Deliberately not through [docIdCache]: both callers use the answer to choose
     * between writing into a document and making a new one, and the cache can still
     * name a document deleted since it was filled.
     */
    private fun findChildDocId(treeUri: Uri, parentDocId: String, name: String): String? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        try {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null, null, null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID
                )
                val nameIndex = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                )
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == name) return cursor.getString(idIndex)
                }
            }
        } catch (e: Exception) {
            Logger.d(tag, "Lookup of $name under $parentDocId failed: ${e.message}")
        }
        return null
    }

    /**
     * Gives an existing device document a new display name, in place.
     *
     * The whole point is what it does *not* touch: the provider moves the document itself,
     * so everything beneath it travels along, including the `.git` and `node_modules` the
     * mirror never held and the files past [MAX_UPLOAD_ENTRIES] it could not have re-sent.
     * Nothing else here can move a subtree that the mirror does not fully contain.
     *
     * @return whether the device now holds the document under [newName]. False covers a
     *   provider that does not support renaming at all, one that refuses this document,
     *   and one that no longer has it.
     */
    private fun renameInSaf(safDocUri: Uri, newName: String): Boolean = try {
        DocumentsContract.renameDocument(context.contentResolver, safDocUri, newName) != null
    } catch (e: Exception) {
        Logger.w(tag, "Could not rename a document to $newName: ${e.message}")
        false
    }

    /**
     * Moves an existing device document into a different parent, in place.
     *
     * The counterpart to [renameInSaf] for the half of the problem a rename cannot
     * express. It carries the subtree for the same reason: the provider relocates the
     * document itself, so the `.git` and `node_modules` the mirror never held travel with
     * it. Without this, dragging `src/util` into `src/legacy/` left the old copy standing
     * on the device, and reopening the folder copied it back down beside the new one.
     *
     * `moveDocument` is a different call from `renameDocument` behind a different flag
     * (`FLAG_SUPPORTS_MOVE` rather than `FLAG_SUPPORTS_RENAME`), so a provider may offer
     * one and not the other. A null return puts the caller back on the pre-existing
     * fallback: build the new name from the mirror and leave the old copy alone.
     *
     * @return the document's URI in its new parent, or null when the provider would not
     *   move it.
     */
    private fun moveInSaf(safDocUri: Uri, sourceParentUri: Uri, targetParentUri: Uri): Uri? = try {
        DocumentsContract.moveDocument(
            context.contentResolver, safDocUri, sourceParentUri, targetParentUri
        )
    } catch (e: Exception) {
        Logger.w(tag, "Could not move a document between directories: ${e.message}")
        null
    }

    /**
     * Deletes a document from the SAF tree.
     */
    private fun deleteFromSaf(safDocUri: Uri) {
        try {
            DocumentsContract.deleteDocument(context.contentResolver, safDocUri)
        } catch (e: Exception) {
            Logger.w(tag, "Failed to delete from SAF: ${e.message}")
        }
    }

    // -- Internal: Write-back Processing --

    private fun processWriteBack(job: SyncJob) {
        // Small debounce: skip if more recent job for same path exists
        if (writeBackQueue.any { it.localPath == job.localPath && it.timestamp > job.timestamp }) {
            return
        }

        Logger.d(tag, "Write-back: ${job.type} ${job.localPath}")

        when (job.type) {
            SyncType.MODIFY -> {
                val localFile = File(job.localPath)
                if (!localFile.exists()) return
                if (job.safDocUri != null) {
                    writeLocalToSaf(localFile, job.safDocUri)
                } else if (job.safParentUri != null && job.safTreeUri != null) {
                    // FileObserver may report MODIFY instead of CREATE for new files
                    // (e.g., `echo > file` on Android API 36). Fall through to CREATE.
                    Logger.d(tag, "MODIFY on unknown file, treating as CREATE: ${localFile.name}")
                    createInSaf(localFile, job.safParentUri, job.safTreeUri)
                }
            }
            SyncType.CREATE -> {
                val localFile = File(job.localPath)
                if (localFile.exists() && job.safParentUri != null && job.safTreeUri != null) {
                    createInSaf(localFile, job.safParentUri, job.safTreeUri)
                }
            }
            SyncType.DELETE -> {
                if (job.safDocUri != null) {
                    deleteFromSaf(job.safDocUri)
                }
                // The enqueue resolved that path to the document it was about to
                // remove, and the resolution is cached. Left behind, the entry
                // answers for a name the device no longer holds: the next event
                // on a recreated path writes into the dead document, and a rename
                // into that name is refused its pair. Nothing else removes these,
                // so the delete that strands them is the delete that clears them.
                if (job.relativePath.isNotEmpty()) {
                    forgetCachedSubtree(job.relativePath)
                }
            }
            SyncType.RENAME -> {
                // safDocUri is the document under the *old* name here, and localPath the
                // new one. Falling back to a create when the provider will not rename is
                // not a nicety: without it a provider lacking FLAG_SUPPORTS_RENAME would
                // leave the new name absent from the device entirely, which is worse than
                // the duplicate this job exists to avoid.
                //
                // A rename that succeeds does not re-upload anything, and it should not:
                // the watcher has been writing every change in that subtree back as it
                // happened, so re-sending it would be a file-by-file rewrite of a copy
                // that is already correct, 1500 provider writes for a renamed `src/`,
                // with every save queued behind them. What that rewrite would also have
                // done is push mirror files that never reached the device because their
                // directory fell past [MAX_WATCHED_DIRECTORIES] and no observer ever
                // reported them. Those stay mirror-only. They were mirror-only before the
                // rename too, this does not lose them, it just stops incidentally
                // rescuing them.
                //
                // A move to a different directory is a second call and has to come first:
                // `moveDocument` returns the document's URI in its new home, and renaming
                // before moving would rename in the old one and then move a URI this code
                // no longer holds. Where the parents match, `safSourceParentUri` is null
                // and nothing here changes.
                //
                // The destination parent is re-resolved when the event could not resolve
                // it, because "could not" is usually timing rather than absence:
                // `mkdir lib && mv util lib/` delivers the MOVED_TO while lib's own
                // create job is still ahead of this one in the queue, and the answer
                // from event time cannot see a document that lands a tick later.
                val localFile = File(job.localPath)
                val targetParentUri = job.safParentUri
                    ?: if (job.safSourceParentUri != null && job.relativePath.isNotEmpty()) {
                        // Only while the cache still belongs to this job's tree. A
                        // drain can outlive its folder, and a cache refilled for the
                        // next one answers a relative path with the wrong folder's
                        // documents; declining costs the documented stale-copy
                        // fallback, which the pre-existing behaviour was anyway.
                        job.safTreeUri?.takeIf { it == cacheTree }?.let {
                            resolveDocumentUri(it, File(job.relativePath).parent ?: "")
                        }
                    } else {
                        null
                    }
                val moved = when {
                    job.safDocUri != null && job.safSourceParentUri != null &&
                        targetParentUri != null ->
                        moveInSaf(job.safDocUri, job.safSourceParentUri, targetParentUri)
                    // A rename inside one directory has nothing to move.
                    job.safSourceParentUri == null -> job.safDocUri
                    // A cross-directory move that could not be attempted has not
                    // happened, whatever an unchanged name implies; reporting it
                    // done is what left the device holding the old parent forever.
                    else -> null
                }
                // Renaming is skipped when the name did not change, which is the common
                // shape of a move: dragging `src/util` into `src/legacy/` keeps the name.
                // Decided from the two paths rather than from the moved document's URI,
                // because a document ID is not required to be a path and reading a name
                // out of one is only right on the providers where it happens to be.
                val nameUnchanged = job.previousName == localFile.name
                val renamed = moved != null && (nameUnchanged || renameInSaf(moved, localFile.name))
                if (!renamed && localFile.exists() &&
                    targetParentUri != null && job.safTreeUri != null
                ) {
                    createInSaf(localFile, targetParentUri, job.safTreeUri)
                }
            }
        }
    }

    // -- FileObserver --

    /**
     * Watches one directory and enqueues write-back jobs for what changes inside it.
     *
     * [path] arrives as inotify's own `name` field — the bare entry name within the
     * watched directory, not a path relative to the mirror root — so [dir] is what it
     * has to be resolved against. Covering the tree is [watchTree]'s job: one observer
     * per directory, because a watch descriptor covers a directory and not a tree.
     */
    private inner class DirectoryObserver(
        private val dir: File,
        private val rootDir: File,
        private val safTreeUri: Uri
    ) : FileObserver(dir, MODIFY or CREATE or DELETE or MOVED_FROM or MOVED_TO) {

        override fun onEvent(event: Int, path: String?) {
            if (path == null || !isWatching) return
            handleMirrorEvent(event, File(dir, path), rootDir, safTreeUri)
        }
    }

    /**
     * Turns one inotify event on [localFile] into the watch bookkeeping and the
     * write-back job it implies.
     *
     * On the engine rather than on [DirectoryObserver] so a test can drive it without
     * one: constructing a [FileObserver] runs a static initializer that reaches native
     * code a JVM test has no way to satisfy, and what happens to the device copy is
     * decided here.
     */
    internal fun handleMirrorEvent(event: Int, localFile: File, rootDir: File, safTreeUri: Uri) {
        // Set before anything resolves: every fill below belongs to this tree, and
        // a processing-time reader uses the field to know whether the cache it is
        // about to read still speaks for the tree its job carries.
        cacheTree = safTreeUri
        val relativePath = localFile.relativeTo(rootDir).path

        val type = when (event and FileObserver.ALL_EVENTS) {
            FileObserver.MODIFY -> SyncType.MODIFY
            FileObserver.CREATE, FileObserver.MOVED_TO -> SyncType.CREATE
            FileObserver.DELETE, FileObserver.MOVED_FROM -> SyncType.DELETE
            else -> return
        }

        // inotify reports this alongside the event type when the entry is a
        // directory, and for a delete it is the only way to know: there is nothing
        // left to stat by then.
        val isDirectory = (event and IN_ISDIR) != 0 || localFile.isDirectory

        // A directory arriving or leaving changes what has to be watched. Unwatching
        // is unconditional because it is a no-op for anything never watched.
        when (type) {
            SyncType.CREATE ->
                if (isDirectory && !shouldSkip(localFile.name, isDir = true)) {
                    watchTree(localFile, rootDir, safTreeUri)
                }
            SyncType.DELETE -> unwatchTree(localFile)
            else -> Unit
        }

        if (!shouldWriteBack(relativePath, isDirectory)) return

        // Placed here rather than in the four write-back branches: this covers
        // CREATE, MODIFY, DELETE and both halves of a move in one statement, and
        // stops the job being queued at all.
        //
        // The provider is asked rather than trusted from the set alone, because
        // the set is a memory of what this sync could not read and the document
        // may have been deleted on the device since. When it has, the local file
        // is an ordinary new file and uploads normally. The binder walk costs
        // something only for paths in the set, which is normally empty.
        if (localFile.absolutePath in unfetched &&
            providerHoldsDocument(safTreeUri, relativePath)
        ) {
            Logger.w(
                tag,
                "Not writing $relativePath back: the device holds a document there " +
                    "that this sync never read, and writing would replace it",
            )
            return
        }

        val now = System.currentTimeMillis()

        // A directory that moved away is one half of a rename, and the device holds the
        // only complete copy of it. Android does not expose inotify's cookie, so the
        // MOVED_FROM and the MOVED_TO cannot be paired by the kernel's own identifier and
        // this half arrives as a plain delete, which on a directory document is
        // `deleteDocument` taking everything beneath it on the device. What the other half
        // gives back is the mirror's copy, and the mirror is not all of it:
        // [createChildrenInSaf] stops at [MAX_UPLOAD_ENTRIES], and the mirror never held
        // the `.git` or `node_modules` that [SKIP_DIRECTORIES] kept out of it, so those go
        // and never return. Renaming a folder of 3000 files therefore left 1000 of them
        // nowhere but the mirror, which is reclaimed as soon as the folder's permission
        // lapses.
        //
        // So the delete is never propagated, it is held instead, for the MOVED_TO that a
        // rename sends a moment later. That one renames the device's *own* document into
        // the new name, which carries the subtree the mirror could not because the
        // provider moves it in place, and leaves nothing under the old name.
        //
        // When nothing claims it the record expires and the device keeps a stale copy
        // under the old name, and that copy is durable, not merely untidy: reopening the
        // folder copies it back down, so the old name reappears in the editor beside the
        // new one, and an unclaimed move leaves one more of them every time. Deleting it
        // in the editor propagates, because an outright delete arrives as DELETE and is
        // still acted on; nothing removes it on the user's behalf.
        if (isDirectory && (event and FileObserver.ALL_EVENTS) == FileObserver.MOVED_FROM) {
            synchronized(vanishedLock) {
                vanishedDirectories.removeAll { now - it.atMillis > RENAME_PAIR_WINDOW_MS }
                vanishedDirectories.add(VanishedDirectory(relativePath, now))
            }
            Logger.i(
                tag,
                "Directory moved away; keeping the device copy of $relativePath until a " +
                    "rename claims it"
            )
            return
        }

        // Resolve the SAF URI for this file via its relative path
        val safDocUri = resolveDocumentUri(safTreeUri, relativePath)
        val safParentUri = resolveDocumentUri(
            safTreeUri,
            File(relativePath).parent ?: ""
        )

        // The other half. Claiming is refused unless the device has nothing under the new
        // name. A null [safDocUri] has already asked: the resolution above missed the
        // cache and walked the provider on this very event, so the walk is only
        // repeated where a non-null answer can be stale, which is the cache hit and
        // the not-yet-visible rename:
        // `renameDocument` handed a name the folder already holds is the same trap as
        // `createDocument`, and providers answer it by inventing "util (1)", which
        // would leave the user two directories again, one of them named nonsense. But
        // the cache can name a document deleted since it was filled, a DELETE job
        // here, or a device-side removal no job of ours saw, and a dead entry
        // answering for the new name is what refuses the pair whose other half is
        // holding the only complete copy. A swap, `mv dist dist.old; mv dist.new
        // dist`, is refused by the same test, because the first rename is still in
        // the queue when the second pair arrives and the device still answers for
        // `dist`. Its second half falls back to being created from the mirror, which
        // is what it did before; its first half still moves in one piece.
        val renamedFrom =
            if (isDirectory &&
                (event and FileObserver.ALL_EVENTS) == FileObserver.MOVED_TO &&
                hasVanishedCandidate() &&
                (safDocUri == null || !providerHoldsDocument(safTreeUri, relativePath))
            ) claimVanishedDirectory(relativePath, now) else null
        // Resolved after the claim rather than when the directory vanished, so that the
        // provider is only asked on the events that turn out to be renames. Null means
        // the device has no document under the old name, there is nothing to rename and
        // nothing to duplicate, so this falls back to creating the new name outright.
        val renamedFromUri = renamedFrom?.let { resolveDocumentUri(safTreeUri, it) }
        if (renamedFrom != null && renamedFromUri != null) forgetCachedSubtree(renamedFrom)

        // Only for a pair that crosses directories, and resolved from where the document
        // actually is rather than from the destination: `moveDocument` refuses a source
        // parent the document is not in. Resolving it unconditionally would ask the
        // provider on every ordinary rename for a value nothing would read.
        val sourceParentUri =
            if (renamedFrom != null && renamedFromUri != null &&
                parentPathOf(renamedFrom) != parentPathOf(relativePath)
            ) resolveDocumentUri(safTreeUri, parentPathOf(renamedFrom)) else null

        writeBackQueue.offer(
            SyncJob(
                type = if (renamedFromUri != null) SyncType.RENAME else type,
                localPath = localFile.absolutePath,
                safDocUri = renamedFromUri ?: safDocUri,
                safParentUri = safParentUri,
                safTreeUri = safTreeUri,
                timestamp = now,
                safSourceParentUri = sourceParentUri,
                previousName = renamedFrom?.let { File(it).name },
                relativePath = relativePath
            )
        )
    }

    /**
     * Whether the provider itself answers for [relativePath], asked segment by
     * segment rather than through [docIdCache].
     *
     * The cache is the right answer for the paths jobs write into, but it is a
     * record of what was true when it was filled, and nothing maintains it
     * against deletions the provider performed. A decision that turns on the
     * device *not* holding a name has to ask the device.
     */
    private fun providerHoldsDocument(treeUri: Uri, relativePath: String): Boolean {
        var currentDocId = DocumentsContract.getTreeDocumentId(treeUri)
        for (segment in relativePath.split("/")) {
            currentDocId = findChildDocId(treeUri, currentDocId, segment) ?: return false
        }
        return true
    }

    /**
     * Whether any vanished directory is still waiting for its other half, without
     * consuming anything.
     *
     * Asked before the provider walk in the claim decision, because it is a lock
     * and a size check while the walk is a binder round trip per path segment on
     * the observer thread, and most directory MOVED_TO events have nothing to
     * pair with: a folder arriving from outside the mirror, a build tool swapping
     * trees. Those used to pay the walk for an answer the cheap check settles.
     */
    private fun hasVanishedCandidate(): Boolean = synchronized(vanishedLock) {
        vanishedDirectories.isNotEmpty()
    }

    /**
     * Takes the vanished directory a MOVED_TO on [newRelativePath] may be the other half
     * of, or null when none of them can be claimed. A claimed entry is consumed.
     */
    private fun claimVanishedDirectory(newRelativePath: String, now: Long): String? =
        synchronized(vanishedLock) {
            val source = renameSourceFor(vanishedDirectories, newRelativePath, now)
            if (source != null) vanishedDirectories.removeAll { it.relativePath == source }
            source
        }

    /**
     * Drops [relativePath] and everything under it from [docIdCache].
     *
     * Called when the device document behind that path has been renamed, which is the one
     * event that invalidates a whole branch of the cache at once. Two things go wrong
     * without it, and both are quiet: on a provider whose document IDs are paths, every
     * cached ID under the old name now names a document that does not exist; and on a
     * provider whose IDs survive a rename, [createOneInSaf]'s reverse lookup can find the
     * *old* path for the renamed document and file new children under a relative path the
     * mirror no longer has. Dropping them costs a tree walk on the next lookup of a path
     * below the new name, which is the slow path that already exists for new files.
     */
    private fun forgetCachedSubtree(relativePath: String) {
        val prefix = "$relativePath/"
        docIdCache.keys.removeAll { it == relativePath || it.startsWith(prefix) }
    }

    /**
     * Resolves a document URI within a SAF tree given a relative path.
     * Uses [docIdCache] for O(1) lookup when available, falling back to tree
     * traversal on a cache miss.
     */
    private fun resolveDocumentUri(treeUri: Uri, relativePath: String): Uri? {
        if (relativePath.isEmpty()) {
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            return DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
        }

        // Fast path: use cached docId if available
        val cachedDocId = docIdCache[relativePath]
        if (cachedDocId != null) {
            return DocumentsContract.buildDocumentUriUsingTree(treeUri, cachedDocId)
        }

        // Slow path: walk the tree segment by segment (for newly created files)
        var currentDocId = DocumentsContract.getTreeDocumentId(treeUri)
        for (segment in relativePath.split("/")) {
            currentDocId = findChildDocId(treeUri, currentDocId, segment) ?: return null
        }

        // Cache for next time
        docIdCache[relativePath] = currentDocId
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, currentDocId)
    }

    private fun guessMimeType(filename: String): String =
        Companion.guessMimeType(filename)

    companion object {
        private const val COPY_BUFFER_SIZE = 8192
        private const val WRITEBACK_POLL_MS = 200L

        /** How long [stopWatching] waits for queued writes to reach the device. */
        private const val DRAIN_GRACE_MS = 2000L

        /** Suffix for a copy still being written; moved into place when complete. */
        private const val PARTIAL_SUFFIX = ".vscodroid-partial"

        /**
         * The file, inside [android.content.Context.getFilesDir], naming every mirror
         * path with an upload in flight. See the journal's users for why it exists.
         */
        internal const val UPLOADS_IN_FLIGHT_FILE = "saf-uploads-in-flight"

        /**
         * Serialises every access to [UPLOADS_IN_FLIGHT_FILE], across engine
         * instances: a write-back drain can outlive the activity whose engine
         * started it, and the next activity's engine reads and rewrites the same
         * one file on its own monitor otherwise.
         */
        private val uploadJournalLock = Any()

        /**
         * Suffix of the sibling file recording what the last complete sync found.
         *
         * A sibling and not a child: the mirror directory is the folder VS Code opens,
         * and a bookkeeping file inside it would show up in the explorer and in search.
         * Being a sibling is also why whatever removes a mirror has to remove this too,
         * which is what [SafStorageManager] uses it for.
         */
        internal const val SYNCED_RECORD_SUFFIX = ".synced"

        /**
         * First line of the record, and the only thing that says which format follows.
         *
         * Field count is not a version. A later format that also has three fields with
         * different meanings would be read as this one and acted on — and acting means
         * deleting. A record whose first line is not exactly this is ignored, which is
         * how a record from the build before headers is already treated, and in the same
         * direction: unverifiable means keep.
         */
        internal const val RECORD_HEADER = "#vscodroid-saf-sync 2"

        /**
         * inotify's flag for "the entry this event is about is a directory".
         *
         * [FileObserver] does not expose it, but it passes the kernel's mask through
         * untouched — which is also why the existing `event and ALL_EVENTS` is needed
         * to read the event type at all.
         */
        private const val IN_ISDIR = 0x40000000

        /**
         * How long a directory that left the mirror stays claimable as a rename source.
         *
         * A rename(2) emits MOVED_FROM and MOVED_TO back to back and `FileObserver`
         * delivers them from one thread in that order, so the true pair is milliseconds
         * apart; this is slack for a busy observer thread, not a guess at how long a user
         * might take. Widening it does not make pairing more likely to succeed, it makes
         * a mis-pair more likely, because it is the only thing bounding how far apart two
         * *unrelated* moves can be and still be joined.
         */
        internal const val RENAME_PAIR_WINDOW_MS = 1_000L

        /**
         * Which vanished directory a MOVED_TO on [newRelativePath] is the other half of,
         * or null when the question cannot be answered.
         *
         * inotify's cookie is what would answer it; `FileObserver` does not expose it, so
         * this is a heuristic and it declines rather than guesses. **When it is wrong:**
         * one directory leaves the mirror for good (`mv dir ~/elsewhere`, a build tool
         * swapping trees) and, within [RENAME_PAIR_WINDOW_MS], an unrelated directory
         * arrives in the same parent. The two are then joined, and the device ends up with
         * the departed directory's subtree under the arriving one's name while the
         * arriving one's own contents stay in the mirror. Nothing is deleted in that
         * case, reopening the folder brings the misplaced subtree back down where it can
         * be seen and moved, which is why the pairing is allowed to be a heuristic at
         * all, and why a rule that cannot be sure says no:
         *
         * - **Exactly one candidate.** Two directories in flight at once cannot be told
         *   apart by arrival order alone once their events interleave, and pairing the
         *   wrong two would put each subtree under the other's name.
         * - **Recent.** See [RENAME_PAIR_WINDOW_MS].
         *
         * A third rule required the two halves to share a parent, and it was dropped once
         * the write-back learned `moveDocument`. It was never about pairing being less
         * certain across parents; it was that `renameDocument` cannot express a move, so a
         * pair the engine could not act on was better left unclaimed. Dragging `src/util`
         * into `src/legacy/` is an ordinary thing to do and used to leave the old copy on
         * the device, reappearing beside the new one on every reopen. Removing the rule
         * does widen the mis-pair window: an unrelated arrival anywhere in the mirror can
         * now be joined to a departure, where before it had to arrive in the same
         * directory. The two surviving rules are what bound it, and the cost of being
         * wrong is unchanged, because nothing is deleted either way.
         *
         * Declining costs exactly what the code did before there was any pairing: the
         * device keeps the copy under the old name.
         */
        internal fun renameSourceFor(
            vanished: List<VanishedDirectory>,
            newRelativePath: String,
            now: Long
        ): String? = vanished
            .filter { now - it.atMillis <= RENAME_PAIR_WINDOW_MS }
            .singleOrNull()
            ?.relativePath

        /** The directory part of a mirror-relative path; empty for a top-level entry. */
        internal fun parentPathOf(relativePath: String): String =
            File(relativePath).parent ?: ""

        /**
         * Ceiling on watched directories.
         *
         * Each watch is a kernel inotify descriptor drawn from a per-uid budget
         * (`fs.inotify.max_user_watches`, commonly 8192), and this process is not the
         * only claimant — the file watcher the VS Code server runs draws on the same
         * pool. [SKIP_DIRECTORIES] already excludes what generates most of the count,
         * so a real project tree lands in the low hundreds; this is the backstop for
         * the folder that does not. Past it, deeper directories go unwatched and their
         * changes stay in the mirror, which is the behaviour the whole engine had
         * before and is better than starving the editor's own watcher.
         */
        private const val MAX_WATCHED_DIRECTORIES = 2048

        /** Max file size to sync (50 MB). Larger files are skipped. */
        internal const val MAX_FILE_SIZE = 50L * 1024 * 1024

        /**
         * How many entries one directory-create is allowed to copy to the device.
         *
         * A rename normally moves a handful of files, but nothing stops it moving a
         * whole project, and every entry here is a provider round trip on the main
         * sync path. The cap bounds that; reaching it is logged, because past it the
         * copy on the device is genuinely incomplete and nothing else would say so.
         */
        internal const val MAX_UPLOAD_ENTRIES = 2000

        /**
         * Whether the mirror copy may be replaced with the one from the device folder.
         *
         * Opening a folder re-runs the whole copy, so this is what stands between a
         * reopen and the loss of edits not yet written back.
         *
         * Timestamps alone are not enough to decide this, for two reasons found the
         * hard way:
         *
         * - A size mismatch beats any timestamp. A copy that fails part-way leaves a
         *   short file carrying a *fresh* mtime, which timestamps alone would read as
         *   "newer, keep it" — freezing the truncation in place forever and letting
         *   the write-back push it onto the device. Different sizes mean the mirror is
         *   not a copy of the source, whatever the clocks say.
         * - An unknown source timestamp (0) must still copy. COLUMN_LAST_MODIFIED is
         *   optional, and treating unknown as "keep" would freeze such folders
         *   permanently: nothing in the app can clear a mirror, so there would be no
         *   way out short of clearing app data. Copying is what the old code did, and
         *   it is the behaviour that heals itself.
         *
         *   ⚠️ Know what it costs before relying on the protections built on top of this.
         *   On such a provider this branch fires every time, so every reopen replaces a
         *   local edit that has not been written back — and [reconcileDeletions]'s record
         *   cannot help, because the file it would have vouched for is already gone. The
         *   folder heals; the edit does not.
         *
         * The comparison is only sound because [copyDocumentToLocal] stamps the mirror
         * with the source's own timestamp, so both sides come from the same clock.
         */
        internal fun shouldOverwriteMirror(
            mirrorExists: Boolean,
            mirrorModified: Long,
            mirrorSize: Long,
            sourceModified: Long,
            sourceSize: Long
        ): Boolean = when {
            !mirrorExists -> true
            // The provider did not report a time. Unknown must not freeze the folder:
            // nothing in the app can clear a mirror, so copying is the behaviour that
            // heals itself.
            sourceModified == 0L -> true
            sourceModified > mirrorModified -> true
            // A newer local copy wins, whatever its size. Checking size before this
            // was the defect: almost every edit changes a file's length, so almost
            // every unsaved edit looked like "not a copy of the source" and was
            // overwritten — the exact case this guard exists for.
            mirrorModified > sourceModified -> false
            // Same timestamp, different content. Writers that preserve mtime — unzip,
            // cp -p, rsync -t, git checkout — land here.
            else -> mirrorSize != sourceSize
        }

        /**
         * Directories to skip during sync — auto-generated and too large.
         *
         * "build" and ".vscode" are deliberately absent. build/ holds real
         * source in many projects (Gradle build scripts, C output kept in
         * tree), and .vscode/ holds launch.json, settings.json and
         * extensions.json — the workspace settings users most want synced.
         * Adding either back silently stops those files reaching the device.
         */
        internal val SKIP_DIRECTORIES = setOf(
            "node_modules",
            ".git",
            "__pycache__",
            ".gradle",
            ".idea",
            "venv",
            ".env"
        )

        /**
         * Whether a provider's display name can stand as one path segment, as it is.
         *
         * `COLUMN_DISPLAY_NAME` is whatever text the provider returned; nothing promises
         * it is a single name. [walkTree] composes it into a relative path and the copy
         * turns that into a real one with [File], creating directories along the way, so
         * a value carrying a separator or a parent reference would land outside the
         * mirror — somewhere the engine has no business writing and no record of having
         * written.
         *
         * Worth knowing when judging the cost: a platform provider derives the name from
         * a real filename and cannot return such a value, so this never fires for the
         * common case. Providers that relay names from elsewhere — cloud, WebDAV, SMB —
         * have no such guarantee.
         *
         * [reconcileDeletions] already declines to act on a path that resolves out of the
         * mirror. This is the same check one step earlier, where the path is composed
         * rather than consumed; having it on only one side was the oversight.
         */
        internal fun isSafeSegment(name: String?): Boolean =
            !name.isNullOrEmpty() && name != "." && name != ".." &&
                name.none { it == '/' || it == '\\' }

        /** Testable: checks if a directory should be skipped during sync. */
        internal fun shouldSkip(name: String, isDir: Boolean): Boolean {
            if (!isDir) return false
            return name in SKIP_DIRECTORIES
        }

        /**
         * The directories a watch has to cover for [root]: [root] itself and everything
         * below it that the walk would have mirrored.
         *
         * The exclusions are [SKIP_DIRECTORIES], the set [walkTree] already obeys —
         * nothing inside them is mirrored in, so a watch there would spend a kernel
         * descriptor on changes with nowhere to go. That is also what keeps the count
         * survivable: one `node_modules` runs to thousands of directories on its own.
         *
         * Breadth-first, and capped at [limit]. The order matters only once the cap
         * bites, and then it decides which directories go unwatched: breadth-first
         * spends the budget on the shallow ones, which is where a person edits, rather
         * than on wherever a depth-first descent happened to reach first.
         */
        /**
         * Everything under [root] that has to be created on the device when [root]
         * itself is created, parents before children.
         *
         * A directory that simply appears in the mirror is the case this serves: without
         * the walk the create put an empty directory on the device and the files stayed
         * in the local mirror, which is reclaimed as soon as the permission lapses.
         *
         * What it cannot do is stand in for a copy the device already has: the cap bounds
         * it, and [SKIP_DIRECTORIES] excludes directories the mirror never held a copy of
         * in the first place. That is why a rename [handleMirrorEvent] could pair goes to
         * `renameDocument` instead of coming through here, and why an unpaired one leaves
         * the old name on the device rather than deleting it and rebuilding from here.
         *
         * Breadth-first so the cap, when it bites, spends the budget on the shallow
         * entries a person is likelier to be looking at. Parents precede children by
         * construction, which the caller needs: a child cannot be created until its
         * parent document exists.
         *
         * Symbolic links are not followed. A mirror is routinely a checked-out
         * repository, so a link inside one is attacker-supplied in the ordinary case,
         * and following it would copy files from outside the folder the user granted.
         */
        internal fun uploadableEntries(root: File, limit: Int): List<File> {
            // [root] is tested for being a link before anything else, and that is not
            // symmetry with the children loop below -- it is the case that reaches
            // outside the folder the user granted. `File.isDirectory` follows links,
            // and so does the observer that produces these jobs
            // (`(event and IN_ISDIR) != 0 || localFile.isDirectory`), so a symlink to
            // a directory arrives here looking exactly like a directory. Walking it
            // would list the *target's* contents and copy them onto the device: point
            // one at a home directory inside a synced folder and the whole of it
            // uploads.
            if (limit <= 0 || isLink(root) || !root.isDirectory) return emptyList()

            val found = mutableListOf<File>()
            val pending = ArrayDeque<File>()
            pending.addLast(root)

            while (pending.isNotEmpty() && found.size < limit) {
                val dir = pending.removeFirst()
                val children = dir.listFiles() ?: continue
                for (child in children.sortedBy { it.name }) {
                    if (found.size >= limit) break
                    if (isLink(child)) continue
                    val isDir = child.isDirectory
                    if (isDir && shouldSkip(child.name, isDir = true)) continue
                    // The same filter every other local-to-device path applies.
                    // shouldWriteBack drops these for single-file events, and the
                    // recursion was the one route around it -- so a directory
                    // appearing while an editor held a `.swp`, or while a mirror
                    // copy was still `.vscodroid-partial`, put that scratch file on
                    // the user's device folder under its temporary name, where
                    // nothing later removes it.
                    if (!isDir && isMachineTemporary(child.name)) continue
                    found.add(child)
                    if (isDir) pending.addLast(child)
                }
            }
            return found
        }

        /**
         * Whether [file] is a symbolic link, without following it.
         *
         * `File.exists()` follows links, so it cannot answer this. Comparing the
         * canonical path against the absolute one can: they differ exactly when some
         * component of the path was a link. The parent is canonicalised first so a
         * link *above* this file -- which is not this file's problem -- does not make
         * every entry beneath it look like one.
         */
        private fun isLink(file: File): Boolean = try {
            val parent = file.parentFile?.canonicalFile ?: return false
            File(parent, file.name).let { it.canonicalPath != it.absolutePath }
        } catch (e: Exception) {
            true
        }

        /**
         * The directories a watch has to cover for [root], one per directory, capped
         * at [limit] and spent breadth-first.
         *
         * Symbolic links are not followed, for the same reason [uploadableEntries]
         * refuses them and with the same words: a mirror is routinely a checked-out
         * repository, so a link inside one is attacker-supplied in the ordinary
         * case, and inotify follows a symlinked path. Watching through one spends
         * this app's descriptor budget on a tree outside the granted folder, and
         * every change there arrives as an event under a mirror-relative path that
         * [shouldWriteBack] happily serves, copying content from outside the grant
         * onto the device.
         */
        internal fun watchableDirectories(root: File, limit: Int): List<File> {
            if (limit <= 0 || isLink(root) || !root.isDirectory) return emptyList()

            val found = mutableListOf<File>()
            val pending = ArrayDeque<File>()
            pending.addLast(root)

            while (pending.isNotEmpty() && found.size < limit) {
                val dir = pending.removeFirst()
                found.add(dir)
                val children = dir.listFiles() ?: continue
                for (child in children) {
                    if (isLink(child)) continue
                    if (child.isDirectory && !shouldSkip(child.name, isDir = true)) {
                        pending.addLast(child)
                    }
                }
            }
            return found
        }

        /** Scratch files the machine makes for itself and is about to rename or drop. */
        private fun isMachineTemporary(name: String): Boolean =
            name.endsWith(PARTIAL_SUFFIX) || name.endsWith("~") || name.endsWith(".tmp")

        /**
         * Whether a change at [relativePath] should be pushed back to the device.
         *
         * This is [shouldSkip] applied to a path rather than to a single entry, so that
         * what the walk declines to mirror in is what this declines to write back out.
         *
         * The rule it replaces tested `relativePath.startsWith(".")`, which had no
         * counterpart on the way in: [SKIP_DIRECTORIES] deliberately does not list
         * `.vscode`, so workspace settings, `.gitignore` and `.editorconfig` were copied
         * into the mirror and then never allowed back onto the device — edited in the
         * editor, saved, and silently lost on the device side.
         *
         * The temporary suffixes are the one asymmetry that stays, and it is the
         * intended one: [PARTIAL_SUFFIX] files are this engine's own half-written
         * copies, and `~`/`.tmp` belong to a writer that is about to rename them away.
         * Uploading them would push a file that is about to stop existing.
         */
        internal fun shouldWriteBack(relativePath: String, isDirectory: Boolean): Boolean {
            if (relativePath.isEmpty()) return false
            val segments = relativePath.split('/')
            val name = segments.last()
            if (isMachineTemporary(name)) return false
            // Every segment but the last is a directory by construction; the last one is
            // only a directory when the caller says so.
            if (segments.dropLast(1).any { shouldSkip(it, isDir = true) }) return false
            return !shouldSkip(name, isDirectory)
        }

        /** Testable: heuristic MIME type detection from filename extension. */
        internal fun guessMimeType(filename: String): String {
            return when {
                filename.endsWith(".txt") || filename.endsWith(".md") -> "text/plain"
                filename.endsWith(".html") -> "text/html"
                filename.endsWith(".js") || filename.endsWith(".ts") -> "text/javascript"
                filename.endsWith(".json") -> "application/json"
                filename.endsWith(".py") -> "text/x-python"
                filename.endsWith(".kt") || filename.endsWith(".java") -> "text/plain"
                filename.endsWith(".xml") -> "text/xml"
                filename.endsWith(".css") -> "text/css"
                filename.endsWith(".sh") -> "text/x-shellscript"
                else -> "application/octet-stream"
            }
        }
    }
}

// -- Data Classes --

internal data class DocumentInfo(
    val uri: Uri,
    val docId: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val size: Long,
    /** Provider's last-modified time, or 0 when it does not report one. */
    val lastModified: Long = 0
)

internal data class SyncJob(
    val type: SyncType,
    val localPath: String,
    val safDocUri: Uri?,
    val safParentUri: Uri?,
    val safTreeUri: Uri?,
    val timestamp: Long,
    /**
     * For a RENAME, the parent the document sits under *now*, which is what
     * `moveDocument` needs alongside the destination and is not recoverable from
     * the other fields: [safDocUri] names the document and [safParentUri] names
     * where it is going. Null on every other job type, and on a rename that
     * stays inside one directory.
     */
    val safSourceParentUri: Uri? = null,
    /**
     * For a RENAME, the name the document currently carries on the device, so
     * the write-back can tell a move that keeps its name from one that does not
     * without reading a name out of a document ID. Null on every other type.
     */
    val previousName: String? = null,
    /**
     * The path of [localPath] relative to the mirror's root, which the write-back
     * thread has no other way to recover: it knows absolute paths and document
     * URIs, and neither names the cache entries or the destination parent a
     * rename needs. Empty only where a job was built by something other than
     * [SafSyncEngine.handleMirrorEvent], which fills it.
     */
    val relativePath: String = ""
)

internal enum class SyncType {
    MODIFY, CREATE, DELETE, RENAME
}

/**
 * A directory that left the mirror and has not been accounted for yet.
 *
 * [relativePath] is where it was, which is all a later MOVED_TO needs to find the
 * document still standing under that name on the device.
 */
internal data class VanishedDirectory(
    val relativePath: String,
    val atMillis: Long
)
