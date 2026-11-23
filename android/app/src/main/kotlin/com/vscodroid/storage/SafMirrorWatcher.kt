package com.vscodroid.storage

import android.net.Uri
import android.os.FileObserver
import com.vscodroid.util.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.concurrent.thread
import com.vscodroid.storage.SafSyncEngine.ChangesDuringOpen
import com.vscodroid.storage.SafSyncEngine.DirectoryObserver
import com.vscodroid.storage.SafSyncEngine.Companion.SYNCED_RECORD_SUFFIX
import com.vscodroid.storage.SafSyncEngine.Companion.RECORD_HEADER
import com.vscodroid.storage.SafSyncEngine.Companion.splitRecordLine
import com.vscodroid.storage.SafSyncEngine.Companion.unescapeRecordPath
import com.vscodroid.storage.SafSyncEngine.Companion.IN_ISDIR
import com.vscodroid.storage.SafSyncEngine.Companion.IN_Q_OVERFLOW
import com.vscodroid.storage.SafSyncEngine.Companion.RENAME_PAIR_WINDOW_MS
import com.vscodroid.storage.SafSyncEngine.Companion.renameSourceFor
import com.vscodroid.storage.SafSyncEngine.Companion.parentPathOf
import com.vscodroid.storage.SafSyncEngine.Companion.MAX_WATCHED_DIRECTORIES
import com.vscodroid.storage.SafSyncEngine.Companion.MAX_UPLOAD_ENTRIES
import com.vscodroid.storage.SafSyncEngine.Companion.SKIP_DIRECTORIES
import com.vscodroid.storage.SafSyncEngine.Companion.uploadPlan
import com.vscodroid.storage.SafSyncEngine.Companion.isLink
import com.vscodroid.storage.SafSyncEngine.Companion.watchableDirectories
import com.vscodroid.storage.SafSyncEngine.Companion.directoryKeyOf
import com.vscodroid.storage.SafSyncEngine.Companion.shouldWriteBack

// Mirror filesystem watching, open-recording replay, overflow reconciliation, and event handling for [SafSyncEngine].

/**
 * Puts an observer on each of [watchableDirectories] for [dir], one per directory,
 * because a watch descriptor covers a directory and not a tree.
 */
internal fun SafSyncEngine.watchTreeImpl(dir: File, rootDir: File, safTreeUri: Uri, owner: WatchSession) {
    // The walk calls listFiles() once per directory, so it stays outside the lock:
    // holding a monitor across a tree's worth of filesystem calls would block the
    // observer thread's own registrations behind them. Its result is a bound on how
    // much work follows, not a reservation; the cap is enforced again below, where
    // it can be exact.
    val room = synchronized(watchersLock) { MAX_WATCHED_DIRECTORIES - watchers.size }
    val targets = watchableDirectories(dir, room)
    // Read outside the locks like the walk, one stat a directory: what its MOVED_FROM cannot read.
    val keys = targets.associateWith { directoryKeyOf(it) }
    val started = mutableListOf<DirectoryObserver>()
    var atLimit = false

    // Under [watchersLock] alone, as [unwatchTree] is. A stop holds [lifecycleLock] while it waits for the
    // write-back thread, and that thread adds watches while it replays what changed as the folder opened: a folder
    // arriving then waited for that lock and the stop waited out its whole grace. A stop sets [isWatching] false
    // before it takes this lock to end every watch, so a start here either comes first and is ended by it, or sees
    // the flag and adds nothing. [startWatching] calls this holding [lifecycleLock], and its call alone hands
    // over the recording it is taking: no observer it starts and no replay runs before that call has returned.
    synchronized(watchersLock) {
        if (shutDown || !isWatching || owner !== session) return
        for (target in targets) {
            if (watchers.size >= MAX_WATCHED_DIRECTORIES) {
                atLimit = true
                break
            }
            // Also for a directory already watched: `mv x a` onto an empty `a` replaces it
            // with no event for the old one, and its key would otherwise go on naming it. Its
            // observer goes too, since it watches the directory that left: kept, nothing
            // watched the files directly inside the one that arrived.
            val key = keys[target]
            val replaced = key?.let { watchedDirectoryKeys.put(target.absolutePath, it) }.let { it != null && it != key }
            val existing = watchers[target]
            if (existing != null && !replaced) continue
            existing?.stopWatching()
            val observer = DirectoryObserver(target, rootDir, safTreeUri, owner)
            watchers[target] = observer
            started += observer
        }
        // Teardown must see every observer that reaches the kernel, and so must
        // [unwatchTree], which takes only this lock: an observer started outside it
        // could be taken out of the map and stopped in between, then started, and
        // never be stopped again. An open's watch on the same directory is handed
        // over only once this one has started, and is not stopped there; see
        // [ChangesDuringOpen.handOver].
        val handing = if (Thread.holdsLock(lifecycleLock)) handingOver else null
        started.forEach {
            it.startWatching()
            it.adopted = handing?.handOver(keys[it.dir])
        }
    }

    if (atLimit || targets.size >= room) {
        Logger.w(
            tag,
            "Watch limit of $MAX_WATCHED_DIRECTORIES reached; directories below " +
                "${dir.name} are unwatched"
        )
    }
}


/**
 * Releases the observer for [dir] and for anything below it. A no-op if unwatched.
 *
 * Under [watchersLock] alone. A stop holds [lifecycleLock] while it waits for the
 * write-back thread, and that thread removes watches while it replays what changed as
 * the folder opened, so a removal that reached that lock just after a stop took it waited
 * out the stop's whole grace. The session check still keeps a late event off the next
 * folder's watches: those are added under [watchersLock] only after that folder's
 * session is published, and started before it is released ([watchTree]).
 */
internal fun SafSyncEngine.unwatchTreeImpl(dir: File, owner: WatchSession) {
    synchronized(watchersLock) {
        if (owner !== session) return
        // Every deleted file reaches here, and only a deleted directory can be
        // holding watches. Without this the scan below would run once per entry of
        // a `git clean`, across the whole map, on the observer thread.
        if (!watchers.containsKey(dir)) return

        val prefix = dir.path + File.separator
        watchers.keys
            .filter { it == dir || it.path.startsWith(prefix) }
            .forEach {
                watchers.remove(it)?.stopWatching()
                watchedDirectoryKeys.remove(it.absolutePath)
            }
    }
}

// -- Internal: Tree Walking --


/**
 * Drops every vanished directory [predicate] answers for, and retires the upload
 * records the files beneath them left behind.
 *
 * One function for all three removals of an *unclaimed* departure, so that the
 * retirement cannot be forgotten at one of them. The fourth removal is deliberately
 * not here: [claimVanishedDirectory] takes an entry precisely because a rename is
 * about to move its records to the new path, which [renameUploadsUnder] does.
 *
 * The journal is touched outside [vanishedLock], because it has a lock of its own
 * and this is the only path that would otherwise hold both.
 */
internal fun SafSyncEngine.dropVanished(predicate: (VanishedDirectory) -> Boolean) {
    val gone = synchronized(vanishedLock) {
        val taken = vanishedDirectories.filter(predicate)
        vanishedDirectories.removeAll(predicate)
        taken.forEach { droppedSaves.remove(it.localPath) }
        taken
    }
    gone.forEach { consumeStaleUploadsUnder(File(it.localPath)) }
}


/**
 * Watches every directory of [mirrorDir] the watcher would, for the open about to run.
 * Best effort: a directory that cannot be watched leaves the rest of the open as it was
 * before anything was recorded, which is what it was on every build before this one, and
 * so does one whose identity cannot be read, which has gone since the walk found it.
 */
internal fun SafSyncEngine.watchChangesDuringOpenImpl(mirrorDir: File): ChangesDuringOpen {
    val changes = ChangesDuringOpen()
    try {
        for (dir in watchableDirectories(mirrorDir, MAX_WATCHED_DIRECTORIES)) {
            val identity = directoryKeyOf(dir) ?: continue
            val watch = directoryWatchFactory(dir) { event, name ->
                if ((event and IN_Q_OVERFLOW) != 0) {
                    changes.markOverflow()
                    Logger.w(tag, "File watcher event queue overflowed while opening ${mirrorDir.name}")
                    return@directoryWatchFactory
                }
                if (name != null) changes.record(event, identity, name, openEventClock())
            }
            changes.watch(dir, identity, watch)
            watch.start()
        }
    } catch (e: Exception) {
        Logger.w(tag, "Not recording changes to ${mirrorDir.name} while it opens: ${e.javaClass.simpleName}")
    }
    return changes
}


internal fun SafSyncEngine.dropChangesDuringOpen() {
    changesDuringOpen.keys.toList().forEach { changesDuringOpen.remove(it)?.close() }
}


/**
 * What the person changed while [mirrorDir] opened, turned into the events this watcher
 * would have seen, in the order they happened, so a folder renamed and named again is
 * still one rename.
 *
 * The open's own work raises events too, and what is on disk once it has finished tells
 * them apart from the person's:
 * - a delete or a move away stands only where nothing is at the path now and the device
 *   held a document there, or will once a rename this pass queued has run, which the job
 *   then looks up ([SyncJob.resolveAtRun]); the open sets a copy aside and puts another in
 *   its place, and removes only what the device no longer holds. A folder moved away also
 *   stands where a folder made since stands at its name (`mv a b; mkdir a`), told apart by
 *   its identity, which goes with it to [handleMirrorEvent] so only its own arrival can
 *   claim it;
 * - a file made or saved stands only where its copy is not the one the open's record
 *   vouches for, which is every copy the open put in place, kept, or sent itself, nor
 *   one the open keeps inside the app with a row without a digest
 *   ([ChangesDuringOpen.keptByOpen]), and only its last event, as one save sends
 *   everything ([ChangesDuringOpen.record] keeps no other);
 * - a folder made stands only where the device holds none, which leaves out every folder
 *   the open made for the device's own.
 *
 * A folder that arrives with the identity of one that left, a rename, is followed by each
 * file under it whose copy no row of the record names under the folder's old name, with a
 * digest or, for a copy the open keeps inside the app, without one: the rename moves the
 * device's copies, and a save made there before the rename was recorded
 * under a name that is no longer there to send. The open writes no row for a folder renamed
 * before it reached it, so then every file in it is sent again. The two halves of a rename are
 * paired by when the open saw them ([ChangesDuringOpen.Recorded.at]), within the second the
 * watcher allows itself. A folder that left and was then removed under the name it arrived at
 * (`mv a b && rm -r b`), where nothing stands by the time this runs, is sent as the removal of
 * the name it left.
 *
 * A stop, a folder switch or a shutdown ends the pass where it is, and tells of nothing it did
 * not reach: that is left as a change made while the folder was closed, which the next open
 * reads as such.
 *
 * A file is sent as a save whatever made it, so [keepsDeviceEdit] still asks about the
 * device's copy, and a delete goes through [keepsDeviceDocument] as any other.
 *
 * The watcher has started when this runs, and what it queues meanwhile waits behind what
 * this queues ([WatchSession.holdLiveJobs]): queued ahead, a save in a folder this makes or
 * renames found no folder on the device and was announced as kept in the app, and missed
 * the rename that would have carried it.
 *
 * The open leaves to this pass the files only the mirror held that the person placed while
 * it ran ([ChangesDuringOpen.leaveToReplay]): it neither keeps them nor tells of them, and
 * sends none that came with a folder renamed meanwhile ([ChangesDuringOpen.renamedIn]). One
 * this pass does not send is told of here, once for the pass.
 *
 * What a switch recorded ([ChangesDuringOpen.afterSwitch]) goes the same way, with the device
 * asked what it holds where an open's record would have answered from the cache it filled. A
 * removal the device could not be asked about is not sent, and the pass goes on.
 *
 * @return false when this pass held back such a removal, for the caller to tell of once, as
 *   of a pass that failed ([onOpenChangesUnsent]).
 */
internal fun SafSyncEngine.replayChangesDuringOpenImpl(
    changes: ChangesDuringOpen,
    mirrorDir: File,
    safUri: Uri,
    owner: WatchSession = session,
): Boolean {
    owner.holdLiveJobs()
    try {
        return replayRecorded(changes, mirrorDir, safUri, owner)
    } finally {
        owner.releaseLiveJobs()
        changes.forget()
    }
}


internal fun SafSyncEngine.replayRecorded(changes: ChangesDuringOpen, mirrorDir: File, safUri: Uri, owner: WatchSession): Boolean {
    changes.close()
    val events = changes.recorded()
    if (events.isEmpty()) return !changes.overflowed
    // The rows with a digest, which every copy the open put in place, kept with its bytes
    // read, or sent itself carries; a row without one only names a file the open could
    // not place. Null where the record cannot be read: then nothing tells the open's own
    // copies from the person's saves, and sending them all would write every file back.
    val rows: List<Pair<String, ByteArray?>>? by lazy {
        readSyncedRecord(File(mirrorDir.path + SYNCED_RECORD_SUFFIX))?.let { lines ->
            if (lines.firstOrNull() != RECORD_HEADER) null else lines.drop(1).map { splitRecordLine(it) }
        }
    }
    val vouched: Set<String>? by lazy { rows?.filter { it.second != null }?.mapTo(HashSet()) { it.first } }
    // Every row, with a digest or not: a copy a row names unchanged under a renamed folder's old name is one the
    // device holds or one the open keeps inside the app, and neither is sent again.
    val named: Set<String>? by lazy { rows?.mapTo(HashSet()) { it.first } }
    val type = { event: Int -> event and FileObserver.ALL_EVENTS }
    val removal = { event: Int -> type(event) == FileObserver.DELETE || type(event) == FileObserver.MOVED_FROM }
    // What this pass sends, files and folders, for what the open left to it below.
    val sent = HashSet<String>()
    val sentDirectories = ArrayList<String>()
    // The folders this pass moved away, by identity, and the ones that arrived with one of those identities.
    val departures = HashSet<Any>()
    val renamedIn = ArrayList<Pair<File, File>>()
    // Of the folders moved away, where each was, until an arrival with its identity is sent: its departure is held
    // for that rename.
    val unclaimed = HashMap<Any, File>()
    // What the device answered for each path a switch recorded, asked once in the pass rather than once per event:
    // a file a program deleted a thousand times was asked about a thousand times, a lookup per path segment each,
    // while every save waited behind the pass. A lookup that failed is kept too, as no answer: asked again for
    // each event, a provider that had gone away was asked as often, and answered no better.
    val heldOnDevice = HashMap<String, Boolean?>()
    // Whether a removal was held back because the device could not be asked; see the removals below.
    var unasked = false
    for ((event, file, identity, at) in events) {
        // A stop, a folder switch or a shutdown ends the pass: the folder is closed, and what is left is as a change
        // made while it was closed. Going on, each change announced itself as kept in the app though nothing had
        // tried to send it.
        if (owner !== session) return true
        // A folder that left and then, under a name it no longer holds, was removed (`mv a b && rm -r b`): its
        // arrival was not sent, nothing being there, nor the deletes under the new name, which the device does not
        // have, so the device kept the folder under its old name and the next open brought it back. Sent as the
        // removal of the old name instead, through the same checks as any; left alone where something stands there
        // now, which the device's folder of that name then serves.
        if (type(event) == FileObserver.DELETE && identity != null) {
            val origin = unclaimed.remove(identity)
            if (origin != null) {
                if (!origin.exists() && !isLink(origin)) {
                    dropVanished { it.localPath == origin.absolutePath }
                    handleMirrorEvent(
                        FileObserver.DELETE or IN_ISDIR, origin, mirrorDir, safUri, owner,
                        replayed = true, recordedAt = at,
                    )
                }
                continue
            }
        }
        val relative = file.toRelativeString(mirrorDir)
        // The cache the open filled, or the device itself for what a switch recorded: no open filled the cache for
        // that, and every delete and rename read as a path the device does not hold and was dropped.
        val onDevice = {
            if (!changes.afterSwitch) cachedDocId(safUri, relative) != null
            else if (relative in heldOnDevice) heldOnDevice[relative]
            else providerHoldsImpl(safUri, relative).also { heldOnDevice[relative] = it }
        }
        // A folder that arrives with the identity of one this pass moved away is the other half of a rename, also
        // where the device holds a folder of its name: the watcher sends it into that folder, as it does live.
        val renamed = identity != null && identity in departures && type(event) == FileObserver.MOVED_TO
        var heldByDevice = false
        val replay = when {
            removal(event) -> when {
                isLink(file) -> null
                // Asked before the device: something stands at the path again, so nothing is removed whatever the
                // device holds there.
                file.exists() && !(type(event) == FileObserver.MOVED_FROM && identity != null &&
                    file.isDirectory && directoryKeyOf(file) != identity) -> null
                // Not on the device under this name may only mean that a rename this pass queued has not run.
                else -> when (if (renameQueuedOver(owner, file)) true else onDevice()) {
                    true -> if ((event and IN_ISDIR) != 0) event else FileObserver.DELETE
                    false -> null
                    // The device could not be asked. Read as not holding it, the removal was dropped with nothing
                    // said and the file came back at the next open; it is held back and told of, as when the
                    // replay fails.
                    null -> null.also { unasked = true }
                }
            }
            (event and IN_ISDIR) != 0 -> event.takeIf {
                // Unanswered reads as not held: the folder is sent, and its create asks the device again.
                heldByDevice = onDevice() == true
                type(it) != FileObserver.MODIFY && file.isDirectory && (!heldByDevice || renamed)
            }
            // A copy the open keeps inside the app with a row without a digest, as one set aside beside a
            // folder that replaced its file: the open's own move of it reaches this record too.
            file.isFile && !isLink(file) && !changes.isKeptByOpen(identityLine(relative, file)) &&
                vouched?.contains(identityLine(relative, file)) == false -> FileObserver.MODIFY
            else -> null
        } ?: continue
        if (!removal(replay)) {
            if ((replay and IN_ISDIR) != 0) sentDirectories += file.absolutePath else sent += file.absolutePath
        }
        // A folder sent on is made whole on the device where its rename cannot be made, as where the device no
        // longer holds the folder it was renamed from, and that create sent the copies the open had kept inside
        // the app, which put a document the device deleted back there under a notice saying it stayed in the app.
        if (!removal(replay) && (replay and IN_ISDIR) != 0 && changes.keptAnyByOpen()) {
            for (copy in uploadPlan(file, Int.MAX_VALUE) { it.isFile }.entries) {
                val row = identityLine(copy.toRelativeString(mirrorDir), copy)
                if (changes.isKeptByOpen(row)) keptFromCreates += keptFromCreatesKey(mirrorDir, row)
            }
        }
        handleMirrorEvent(
            replay, file, mirrorDir, safUri, owner,
            departedKey = identity.takeIf { removal(replay) }, replayed = true, recordedAt = at,
        )
        if (identity == null || (replay and IN_ISDIR) == 0) continue
        if (type(replay) == FileObserver.MOVED_FROM) {
            departures += identity
            unclaimed[identity] = file
        } else if (renamed) {
            unclaimed.remove(identity)
            // Sent whole where the device holds the name, the rename being refused there, and where the arrival was
            // not paired with the departure, as when it came more than the window after it: its create sends every
            // file in it, and following it with each file again wrote each a second time and looked each up twice.
            if (!heldByDevice && owner.pending().any { it.type == SyncType.RENAME && it.localPath == file.absolutePath }) {
                changes.origin(identity)?.let { renamedIn += file to it }
            }
        }
    }
    for ((dir, origin) in renamedIn) {
        if (owner !== session) return true
        for (file in uploadPlan(dir, Int.MAX_VALUE) { it.isFile }.entries) {
            if (owner !== session) return true
            val relative = file.toRelativeString(mirrorDir)
            val before = File(origin, file.toRelativeString(dir)).toRelativeString(mirrorDir)
            if (file.absolutePath in sent || changes.isKeptByOpen(identityLine(relative, file)) ||
                named?.contains(identityLine(before, file)) != false
            ) continue
            sent += file.absolutePath
            handleMirrorEvent(FileObserver.MODIFY, file, mirrorDir, safUri, owner, replayed = true)
        }
    }
    if (owner !== session) return true
    // The files only the mirror held that the open left to this pass rather than keep, and that it did not send:
    // the guards above refused them, as where the record cannot be read. They stay in the app, and the open said
    // nothing of them, so it is said here. One the device holds by now is not among them.
    val unsent = changes.leftToReplay().filter { path ->
        val file = File(path)
        path !in sent && sentDirectories.none { path.startsWith(it + File.separator) } &&
            file.isFile && !isLink(file) && cachedDocId(safUri, file.toRelativeString(mirrorDir)) == null
    }.map { File(it).toRelativeString(mirrorDir) }.distinct().sorted()
    if (unsent.isNotEmpty()) onFilesKeptInApp(mirrorDir, unsent.size, unsent.first())
    return !unasked && !changes.overflowed
}

// -- FileObserver --


internal fun SafSyncEngine.handleOverflow(
    rootDir: File,
    safTreeUri: Uri,
    owner: WatchSession,
    reason: String = "inotify queue overflow",
) {
    owner.overflowed = true
    if (!owner.overflowPending.compareAndSet(false, true)) return
    Logger.w(tag, "File watcher event queue overflowed for ${rootDir.name} ($reason); rescanning watched directories")
    owner.scheduleObserverOverflow(observerQueueCapacity) {
        reconcileAfterOverflow(rootDir, safTreeUri, owner)
    }
}


internal fun SafSyncEngine.pruneStaleWatchers(owner: WatchSession) {
    val current = synchronized(watchersLock) {
        if (owner !== session) return
        watchers.keys.toList()
    }
    val stale = current.filter { !it.isDirectory || directoryKeyOf(it) == null }
    if (stale.isEmpty()) return
    synchronized(watchersLock) {
        if (owner !== session) return
        for (dir in stale) {
            if (!dir.isDirectory || directoryKeyOf(dir) == null) {
                watchers.remove(dir)?.stopWatching()
                watchedDirectoryKeys.remove(dir.absolutePath)
            }
        }
    }
}


internal fun SafSyncEngine.reconcileAfterOverflow(rootDir: File, safTreeUri: Uri, owner: WatchSession) {
    owner.overflowPending.set(false)
    if (shutDown || !isWatching || owner !== session) return
    pruneStaleWatchers(owner)
    watchTreeImpl(rootDir, rootDir, safTreeUri, owner)
    val lines = readSyncedRecord(File(rootDir.path + SYNCED_RECORD_SUFFIX))
    if (lines?.firstOrNull() != RECORD_HEADER) return
    val known = lines.drop(1).mapTo(HashSet()) { splitRecordLine(it).first }
    val knownFolders = HashSet<String>()
    for (row in known) {
        var folder = parentPathOf(unescapeRecordPath(row.substringBefore('\t')))
        while (folder.isNotEmpty() && knownFolders.add(folder)) folder = parentPathOf(folder)
    }
    val createdDirs = ArrayList<String>()
    for (entry in uploadPlan(rootDir, MAX_UPLOAD_ENTRIES).entries) {
        if (shutDown || !isWatching || owner !== session) return
        val path = entry.absolutePath
        if (createdDirs.any { path.startsWith(it) }) continue
        val relative = entry.toRelativeString(rootDir)
        if (entry.isDirectory) {
            if (relative !in knownFolders && cachedDocId(safTreeUri, relative) == null) {
                createdDirs += path + File.separator
                handleMirrorEvent(FileObserver.CREATE or IN_ISDIR, entry, rootDir, safTreeUri, owner)
            }
        } else if (identityLine(relative, entry) !in known) {
            handleMirrorEvent(FileObserver.MODIFY, entry, rootDir, safTreeUri, owner)
        }
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
 *
 * [departedKey] is the identity of a directory that left, for one this watcher never
 * watched and the open did ([replayChangesDuringOpen]); [replayed] marks an event of that
 * replay, whose job goes ahead of the live watcher's ([WatchSession.holdLiveJobs]), and
 * [recordedAt] is when the open's watch saw it ([ChangesDuringOpen.Recorded.at]), which is
 * what a rename's two halves are paired by in a replay ([renameSourceFor]).
 */
internal fun SafSyncEngine.handleMirrorEventImpl(
    event: Int,
    localFile: File,
    rootDir: File,
    safTreeUri: Uri,
    sourceSession: WatchSession = session,
    departedKey: Any? = null,
    replayed: Boolean = false,
    recordedAt: Long? = null,
) {
    // The session this event belongs to, captured rather than read at the offer at the
    // end. Everything in between is provider round trips, a query per path segment
    // and several of them, and a stop can run to completion inside that window: it
    // installs a fresh session that nothing drains, since only [startWatching]
    // starts a worker and it opens with a stop that clears that queue again. A save
    // offered there went nowhere and said nothing. Read at entry, a late offer lands
    // in the queue the closing folder's drain is still emptying, which is what the
    // grace period exists for; if that drain has already finished, the offer below
    // says so, because the stop's own count cannot. That count is read and the queue
    // cleared in microseconds on an idle folder, while this event is still inside the
    // provider, so the one job it can see is one that was already queued.
    //
    // A capture rather than a liveness re-check, because the answer has to be the
    // same object for the whole event: two threads polling one queue is what the
    // session split exists to prevent, and this offers into a queue exactly one
    // worker was ever started for.
    //
    // The observer captures its owner when registered, so even a callback paused
    // before entering here cannot adopt a reopened folder's session or generation.
    val target = sourceSession
    val access = outboundAccess(rootDir, target.generation.takeIf { target.root == rootDir })
    // Nothing here touches the cache's label. An event whose tree the cache does not
    // speak for is one that was still inside its provider round trips when the
    // folder was switched, arriving after the next folder's sync has relabelled the
    // cache and is filling it; every resolution below then walks the provider and
    // caches nothing, which [cachedDocId] and [cacheDocId] decide per access. This
    // used to clear the cache and adopt it for the event's own tree, and the clear
    // and the sync's fill interleaved: the sync went on writing its entries under
    // the old folder's label, the event wrote the old folder's directories beside
    // them, and the new folder's phase 2b read one of those for a same-named path.
    // Where one granted folder sits inside another, that directory is writable, and
    // the write opens it with "wt", which truncates at open. A drain that outlived
    // its folder is unaffected either way: its one cache read is guarded by the same
    // label (see [processWriteBack]'s late-parent resolution).
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
    if (target !== session) {
        if (type != SyncType.DELETE && shouldWriteBack(relativePath, isDirectory)) {
            onWriteBackFailed(localFile)
        }
        return
    }

    val expectedMove = if (isDirectory) target.normalizedDirectories.remove(localFile.absolutePath) else null
    val normalizedMove = expectedMove != null &&
        (event and FileObserver.ALL_EVENTS) == FileObserver.MOVED_TO &&
        runCatching {
            Files.readAttributes(localFile.toPath(), BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS).fileKey()
        }.getOrNull() == expectedMove.second

    // Read before the watch goes below: a directory that has left cannot be asked what it is.
    val departed = if (isDirectory && type == SyncType.DELETE) {
        departedKey ?: watchedDirectoryKeys[localFile.absolutePath]
    } else null

    // A directory arriving or leaving changes what has to be watched. A live departure
    // unwatches unconditionally, since that is a no-op for anything never watched.
    //
    // A replayed departure stops only the watch of the folder that left: the watcher has started by then, and a
    // folder made since at the same name, which it watches, went unheard for the rest of the session. A replayed
    // arrival the watcher already watches under its identity, which its start walked, adds nothing, and is not
    // made to wait for the lock a stop holds while it waits for this thread.
    when (type) {
        SyncType.CREATE ->
            if (isDirectory && !shouldSkip(localFile.name, isDir = true) &&
                !(replayed && watchedDirectoryKeys[localFile.absolutePath].let { it != null && it == directoryKeyOf(localFile) })
            ) {
                watchTreeImpl(localFile, rootDir, safTreeUri, target)
            }
        SyncType.DELETE ->
            if (departedKey == null || watchedDirectoryKeys[localFile.absolutePath] == departedKey) {
                unwatchTreeImpl(localFile, target)
            }
        else -> Unit
    }

    if (normalizedMove) {
        synchronized(vanishedLock) {
            vanishedDirectories.removeAll { it.localPath == expectedMove!!.first }
            droppedSaves.remove(expectedMove!!.first)
        }
        return
    }

    if (!shouldWriteBack(relativePath, isDirectory)) return

    if (type == SyncType.DELETE) {
        clearRefusalNotice(localFile, isDirectory)
    }

    // Placed here rather than in the write-back branches: this covers CREATE,
    // MODIFY and the arriving half of a move in one statement, and stops the job
    // being queued at all.
    //
    // A delete is excluded and handled below instead, and the exclusion is the
    // whole of why the type is read here at all. Both refusals are correct and
    // they are correct for opposite reasons: a refused write leaves the edit
    // inside the app, a refused delete leaves the document on the device and
    // nowhere else. Answering for a delete here said the first sentence about
    // the second situation, sending the user into the app after a file the app
    // had never held, and spent that path's one refusal notice on it, so the
    // save that came afterwards, the one that really did stay inside the app,
    // was silent for the rest of the session.
    //
    // The provider is asked rather than trusted from the set alone, because
    // the set is a memory of what this sync could not read and the document
    // may have been deleted on the device since. When it has, the local file
    // is an ordinary new file and uploads normally. The binder walk costs
    // something only for paths in the set, which is normally empty.
    //
    // The set is therefore tested twice, here and inside the predicate, and the
    // outer one is not a duplicate: it is what keeps [providerHoldsDocument] off
    // the common path. [createOneInSaf] needs no such guard, because there the
    // provider's answer is already in hand.
    if (type != SyncType.DELETE &&
        holdsUnread(localFile.absolutePath, isDirectory) &&
        writeWouldReplaceUnreadDocument(
            localFile.absolutePath,
            providerHoldsDocumentImpl(safTreeUri, relativePath),
        )
    ) {
        refuseUnreadDocument(localFile, relativePath)
        return
    }

    val now = monotonicClock()

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
    // new one, and an unclaimed move leaves one more of them every time. Nothing
    // removes it on the user's behalf, and the Explorer's own Delete does not
    // either: measured on an API 37 emulator, the workbench deletes a folder by
    // renaming it into its temporary directory first, so what arrives here is
    // this MOVED_FROM, the pair never comes, and the device keeps the folder. An
    // outright DELETE for a directory comes from a real unlink, `rm -r` in the
    // terminal, `git clean`, a build script, and is acted on below unless the
    // directory holds documents this sync never read.
    if (isDirectory && (event and FileObserver.ALL_EVENTS) == FileObserver.MOVED_FROM) {
        dropVanished { now - it.atMillis > RENAME_PAIR_WINDOW_MS }
        synchronized(vanishedLock) {
            vanishedDirectories.add(
                VanishedDirectory(relativePath, now, localFile.absolutePath, departed, recordedAt)
            )
            // The move landed on disk before this event, and the write-back thread may have dropped
            // a save of a file inside the directory in between, with nothing yet to note it with.
            val prefix = localFile.absolutePath + File.separator
            val early = target.recentDrops.filter { it.first.startsWith(prefix) }
            target.recentDrops.removeAll(early.toSet())
            val recent = early.filter { now - it.second <= RENAME_PAIR_WINDOW_MS }.map { it.first }
            if (recent.isNotEmpty()) droppedSaves.getOrPut(localFile.absolutePath) { ArrayList() } += recent
        }
        Logger.i(
            tag,
            "Directory moved away; keeping the device copy of $relativePath until a " +
                "rename claims it"
        )
        return
    }

    // A delete the device holds the only copy of, at either level.
    //
    // On a directory document `deleteDocument` takes everything beneath it on the
    // device, and the mirror is not all of it: a child skipped for size, or one
    // whose copy failed, was never in the mirror, so the editor showed the
    // directory without it, the user deleted what they could see, and the device
    // lost a 60 MB archive nothing on screen had mentioned. A file arrives here for
    // the same reason one step down: what the user deleted in the editor was a
    // mirror entry that was never the document, and the document on the device is
    // the only copy of itself.
    //
    // Matched by prefix and by its own path for a directory, since a directory
    // holds what is under it and [unfetched] carries a file for each document this
    // sync did not read plus the directory itself where it read none of them, and
    // by its own path or an unread ancestor for a file. The separator keeps
    // `docs` from matching `docs2` and `notes.md.bak` from matching `notes.md`.
    //
    // Below the MOVED_FROM hold rather than above it, deliberately. A directory
    // with unread children is renamed by the same event pair as any other, and
    // the hold is what turns the pair into one provider move, which is the one
    // path that carries those children to the new name. Refusing the MOVED_FROM
    // would have left the pair unclaimed: the new name created from the mirror
    // without them, the old one standing on the device with them. Only an
    // outright DELETE therefore reaches here as a directory. A file has no pair to
    // protect, so both halves of its delete, the unlink and the move away, reach
    // this and are kept the same way.
    //
    // The provider is asked for the reason the write guard asks it: the set is a
    // memory, and the document may have been deleted on the device since, in
    // which case there is nothing left to keep. Only a provider that positively
    // answers "gone" lets the delete through. An unavailable or partial listing
    // therefore keeps the document, as the write guard keeps an unread document. One scan of a set that is normally
    // empty, on the observer thread; the binder walk is paid only on a match.
    if (type == SyncType.DELETE) {
        val unread = holdsUnread(localFile.absolutePath, isDirectory)
        if ((unread || isDirectory && holdsKept(localFile.absolutePath)) &&
            providerHoldsImpl(safTreeUri, relativePath) != false
        ) {
            keepUnread(localFile, relativePath, isDirectory, changed = !unread)
            return
        }
    }

    // Resolve the SAF URI for this file via its relative path, except where
    // nothing reads it. A file arriving as CREATE (which is also where a file's
    // MOVED_TO lands) is the one shape with no reader: the claim gate below is
    // reached only by a directory, and [processWriteBack]'s CREATE arm takes the
    // parent and the tree and never the document. Resolving anyway is not free and
    // is paid on the worst thread for it: a name the mirror has just gained misses
    // [docIdCache] by construction, so every one of them walked the provider a
    // query per path segment, each query a full linear scan of that directory's
    // cursor, inline on FileObserver's single process-wide ObserverThread, which is
    // also the thread that has to keep up with every inotify event for the whole
    // mirror. A `git checkout` adding two thousand files paid it two thousand
    // times for an answer nothing looked at, while the events behind it queued.
    // [createOneInSaf] asks the provider for the same child again on the path that
    // does need the answer, so nothing here is lost by not asking early.
    //
    // Nor for a replayed path under a folder whose rename the replay has queued: the device holds it, if at all,
    // under the old name until that rename runs, so the lookup is not answered, and a miss is not remembered. Each
    // file of a folder renamed while the open ran cost two provider walks, on the write-back thread, while every
    // live save waited behind the replay. The job looks its folder up when it runs ([createInQueuedParent],
    // [resolvedAtRun]). Asked only on a miss for the folder, so a path whose folder the device holds pays nothing.
    val parentPath = File(relativePath).parent ?: ""
    val underQueuedRename = replayed && cachedDocId(safTreeUri, parentPath) == null &&
        renameQueuedOver(target, localFile)
    val safDocUri =
        if (type == SyncType.CREATE && !isDirectory || underQueuedRename) null
        else resolveDocumentUriImpl(safTreeUri, relativePath, access)
    val safParentUri = if (underQueuedRename) null else resolveDocumentUriImpl(safTreeUri, parentPath, access)

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
            hasVanishedCandidate(now) &&
            (safDocUri == null || !providerHoldsDocumentImpl(safTreeUri, relativePath))
        ) claimVanishedDirectory(relativePath, now, target, directoryKeyOf(localFile), recordedAt) else null
    // The upload records follow the directory, and the claim is the only point that
    // can move them: it is the one place holding both names. The job carries only
    // the old base name, and a RENAME job can be superseded by a newer one, cleared
    // by a stop, or never run at all, while this has to be true from the instant the
    // disk changed.
    //
    // Nothing here is conditioned on what the device does. A line records that the
    // MIRROR holds an edit the device never received, so a provider that refuses the
    // rename, or an old name that resolves to no document, changes nothing about
    // where those files now are. Left behind, the line names a path that no longer
    // exists: the next sync cannot consume it (its repair branch needs the file to
    // be there), so it copies the device's own truncated upload over the mirror's
    // only complete copy, and until then it refuses the removal the user asked for.
    //
    // Only a claimed pair, because only a claimed pair re-uploads nothing. An
    // unclaimed one falls back to createInSaf, which writes every child out again
    // and brackets each write with the journal itself.
    //
    // This is also one of the two places where a wrong claim can cost bytes rather
    // than placement, because these lines land under the ARRIVING directory's paths
    // and the next sync's repair acts on whatever real file it finds there. What
    // keeps a claim from being wrong, and where it still can be, is in [renameSourceFor].
    //
    // This runs on the observer thread while write-backs run on their own, so a save
    // can be streaming out of the directory that is moving. Its claim travels with
    // the lines, which is why [UploadClaim] carries a path rather than being found by
    // one: a writer that released the name it opened with would leave the moved line
    // standing for a write that landed.
    if (renamedFrom != null) {
        withCurrentMemory(access) {
            renameUploadsUnder(File(rootDir, renamedFrom), localFile)
        }
    }
    // Resolved after the claim rather than when the directory vanished, so that the
    // provider is only asked on the events that turn out to be renames. Null means
    // the device has no document under the old name, there is nothing to rename and
    // nothing to duplicate, so this falls back to creating the new name outright.
    val renamedFromUri = renamedFrom?.let { resolveDocumentUriImpl(safTreeUri, it, access) }
    if (renamedFrom != null && renamedFromUri != null) {
        withCurrentMemory(access) { forgetCachedSubtree(safTreeUri, renamedFrom) }
    }
    // A source the device holds under another name until a rename still queued, of it or of a folder above it,
    // has run, as after `mv a b; mv b c` or `mv a b; mv b/sub b/sub2` inside one poll. Its job looks it up
    // then ([resolvedAtRun]); declined here, the folder was made again under its new name beside the one that
    // rename carries.
    val sourceAfterRename = renamedFrom != null && renamedFromUri == null &&
        renameQueuedOver(target, File(rootDir, renamedFrom))

    // Only for a pair that crosses directories, and resolved from where the document
    // actually is rather than from the destination: `moveDocument` refuses a source
    // parent the document is not in. Resolving it unconditionally would ask the
    // provider on every ordinary rename for a value nothing would read.
    val crossesDirectories = renamedFrom != null && renamedFromUri != null &&
        parentPathOf(renamedFrom) != parentPathOf(relativePath)
    val sourceParentUri =
        if (crossesDirectories) {
            resolveDocumentUriImpl(safTreeUri, parentPathOf(renamedFrom!!), access)
        } else {
            null
        }

    // A pair that crossed directories and whose source parent did not resolve is not
    // a rename this write-back can carry out, so it is not offered as one. Both
    // reasons for a null source parent used to arrive at the same place -- a pair
    // inside one directory, which genuinely has nothing to move, and a cross-directory
    // pair whose old parent the provider would not answer for -- and the arm they
    // shared hands the write-back the document under its OLD name with nothing to
    // move it. On the common shape of a move, dragging `src/util` into `src/legacy/`,
    // the name does not change either, so the write-back performed no call at all and
    // still reported the move done: the device kept `src/util`, never received
    // `src/legacy/util`, and the create fallback that would have delivered it was
    // skipped. Declining the pairing puts the job back on the ordinary create path,
    // which is what an unclaimed move has always fallen back to, and leaves the old
    // copy standing on the device where the user can see it.
    val pairedRename = renamedFromUri != null && !(crossesDirectories && sourceParentUri == null) || sourceAfterRename

    // Asked before the offer, never after. A session that has stopped being polled
    // never starts again, so an answer taken here cannot go stale in the two
    // instructions that follow, while the other order would report a job the drain
    // had already taken as lost. Still offered: the queue is the honest record of
    // what was asked for, and the mirror keeps the file either way. What is missing
    // is the user knowing, and that is what this says, in the log and in a notice.
    if (target.abandoned) {
        Logger.w(
            tag,
            "Write-back of $relativePath arrived after the drain ended; the save is " +
                "in the mirror only until the folder is opened again",
        )
        // Told as the stop tells of what it clears: the editor shows the file as saved. Not for
        // a delete, which leaves the device holding the document rather than the app.
        if (type != SyncType.DELETE) announceLost(localFile)
    }

    // The saves the write-back thread dropped for this directory are added by the rename's job when it
    // runs, because that thread can still take one between here and there. A claim that is not paired
    // has no such job, so nothing is kept for it.
    if (renamedFrom != null && !pairedRename) takeClaimedDrops(target, File(rootDir, renamedFrom).absolutePath)
    val renamedSaves = if (pairedRename) {
        val prefix = File(rootDir, renamedFrom).absolutePath + File.separator
        target.pending().filter {
            (it.type == SyncType.MODIFY || it.type == SyncType.CREATE) && it.localPath.startsWith(prefix)
        }.map { relativePath + "/" + it.localPath.removePrefix(prefix) }.distinct()
    } else emptyList()
    target.offer(
        SyncJob(
            type = if (pairedRename) SyncType.RENAME else type,
            localPath = localFile.absolutePath,
            safDocUri = if (pairedRename) renamedFromUri else safDocUri,
            safParentUri = safParentUri,
            safTreeUri = safTreeUri,
            timestamp = now,
            safSourceParentUri = sourceParentUri,
            previousName = if (pairedRename) renamedFrom?.let { File(it).name } else null,
            relativePath = relativePath,
            isDirectory = isDirectory,
            mirrorRoot = rootDir,
            generation = access.generation,
            renamedSaves = renamedSaves,
            previousPath = if (pairedRename) File(rootDir, renamedFrom).absolutePath else null,
            // A delete that named nothing because the device holds it under another name until a queued rename runs.
            resolveAtRun = sourceAfterRename ||
                type == SyncType.DELETE && safDocUri == null && renameQueuedOver(target, localFile),
        ),
        live = !replayed,
    )
    // A folder the device does not hold, renamed while a move of a device folder into it is still queued, as
    // `mkdir sub; mv old sub/mid; mv sub sub2` inside one poll does: nothing renames it on the device, so the move
    // follows it to the new name and goes behind the create just queued, which makes the folder it moves into
    // and leaves what the move brings to it ([createChildrenInSaf]). Left at the old name, the move found no
    // folder there and no folder at its path either, so it ran nowhere and said nothing: the device kept `old`,
    // and the create made `sub2/mid` from the copy without its `.git`.
    if (renamedFrom != null && !pairedRename) {
        followCreatedFolder(target, File(rootDir, renamedFrom), localFile, rootDir, live = !replayed)
    }
}


/**
 * Moves the queued renames of a device folder from outside [from] into it to the same place under [to], behind
 * everything queued; see above. Every job looked up when it runs ([SyncJob.resolveAtRun]) that names what one of
 * those renames brings, or what a job followed for that reason brings in turn, goes behind it, its paths in [from]
 * moved to [to]: `mv sub/mid sub/inner` or `rm -r sub/mid` after the move into `sub`. Left in place, it ran before
 * the move and found nothing on the device: the rename made the folder again from the copy beside the one the move
 * then brought, and the delete left the moved folder standing. A rename inside [from] whose document the device
 * already holds is left where it is: its document is not moving into the folder, and followed, it ran in the
 * device's old folder while the create left its new path out.
 */
internal fun SafSyncEngine.followCreatedFolder(session: WatchSession, from: File, to: File, root: File, live: Boolean) {
    val prefix = from.absolutePath + File.separator
    fun moved(path: String) = if (path.startsWith(prefix)) File(to, path.removePrefix(prefix)).absolutePath else path
    // Where the renames followed so far put their documents, as queued: a job looked up at run that names a path
    // there waits for one of them.
    val brought = ArrayList<String>()
    val sources = ArrayList<Pair<String, String>>()
    session.requeue(live) { job ->
        val named = if (job.type == SyncType.RENAME) job.previousPath else job.localPath
        val movesIn = job.type == SyncType.RENAME && job.localPath.startsWith(prefix) &&
            job.previousPath?.startsWith(prefix) == false
        val waits = job.resolveAtRun && named != null && brought.any { named == it || named.startsWith(it + File.separator) }
        if (!movesIn && !waits) return@requeue null
        if (job.type == SyncType.RENAME) brought += job.localPath
        val previous = job.previousPath?.let(::moved)
        if (previous != null && previous != job.previousPath) sources += job.previousPath!! to previous
        if (!job.localPath.startsWith(prefix)) return@requeue job.copy(previousPath = previous)
        val local = File(moved(job.localPath))
        val relative = local.relativeTo(root).path
        job.copy(
            localPath = local.absolutePath,
            relativePath = relative,
            // Not on the device yet; looked up when the move runs, after the create has made it.
            safParentUri = null,
            previousPath = previous,
            renamedSaves = job.renamedSaves.map {
                if (it.startsWith(job.relativePath + "/")) relative + it.removePrefix(job.relativePath) else it
            },
        )
    }
    // The saves dropped for a rename's source are filed under that source's path; see [takeClaimedDrops].
    if (sources.isNotEmpty()) synchronized(vanishedLock) {
        for ((old, new) in sources) {
            val saves = session.claimedDrops.remove(old) ?: continue
            session.claimedDrops[new] = saves.mapTo(ArrayList()) { new + it.removePrefix(old) }
        }
    }
}


/**
 * Whether a rename of [file], or of a folder above it, is queued or being sent and has not landed, so the device
 * holds what [file] names, if anything, under another name until it has; see [SyncJob.resolveAtRun]. The one being
 * sent counts: seen while it is on its way to the device, the next rename found no source there and made the
 * folder again under its new name.
 */
internal fun SafSyncEngine.renameQueuedOver(session: WatchSession, file: File): Boolean {
    val path = file.absolutePath
    return session.underway().any {
        it.type == SyncType.RENAME && (path == it.localPath || path.startsWith(it.localPath + File.separator))
    }
}


/** Whether [job] renames [path], or a folder above it, away to another name. */
internal fun SafSyncEngine.renamesAway(job: SyncJob, path: String): Boolean {
    val from = job.previousPath ?: return false
    return job.type == SyncType.RENAME && (path == from || path.startsWith(from + File.separator))
}


/**
 * Whether [path] is a document this sync never read or, for a directory, holds one at
 * any depth: what [handleMirrorEvent]'s delete guard asks, and [keepsDeviceDocument]
 * again where the delete happens.
 *
 * A directory is matched by its own path as well as by prefix. It is recorded by its
 * own mirror path when this sync did not read what is under it, either because the name
 * is skipped or because enumerating it failed. Files under unread directories are held
 * too. The prefix test for descendants alone cannot match
 * that entry: `below` carries a trailing separator the entry does not have. Deleting the
 * directory itself is exactly the case those entries exist to refuse. Both guards ask
 * [holdsKept] of a directory too.
 */
internal fun SafSyncEngine.holdsUnread(path: String, isDirectory: Boolean): Boolean {
    if (writeWouldReplaceUnreadDocument(path, true)) return true
    if (!isDirectory) return false
    val below = path + File.separator
    return unfetched.any { it.startsWith(below) }
}


/**
 * Whether the directory [path] holds a file whose delete [keepsDeviceDocument] declined
 * ([keptOnDevice]), which deleting the directory would take with it. Its own question
 * rather than part of [holdsUnread], because the notice says a different thing for it.
 */
internal fun SafSyncEngine.holdsKept(path: String): Boolean {
    val below = path + File.separator
    return keptOnDevice.any { it.startsWith(below) }
}


/**
 * Notes a save, a create or a held save dropped for a file that is not at its path, if a directory
 * that left the mirror held it, or a rename has claimed that directory and its job has not run yet,
 * and otherwise for a moment in [WatchSession.recentDrops], for the MOVED_FROM of a directory whose
 * move has landed and has not been reported yet.
 */
internal fun SafSyncEngine.rememberDroppedSave(session: WatchSession, absolutePath: String) {
    val now = monotonicClock()
    synchronized(vanishedLock) {
        var noted = false
        for (entry in vanishedDirectories) {
            if (absolutePath.startsWith(entry.localPath + File.separator)) {
                droppedSaves.getOrPut(entry.localPath) { ArrayList() } += absolutePath
                noted = true
            }
        }
        for ((directory, saves) in session.claimedDrops) {
            if (absolutePath.startsWith(directory + File.separator)) {
                saves += absolutePath
                noted = true
            }
        }
        session.recentDrops.removeAll { now - it.second > RENAME_PAIR_WINDOW_MS }
        if (!noted) session.recentDrops += absolutePath to now
    }
}


/** The saves dropped for files under the claimed [directory], which leave the session. */
internal fun SafSyncEngine.takeClaimedDrops(session: WatchSession, directory: String): List<String> =
    synchronized(vanishedLock) { session.claimedDrops.remove(directory).orEmpty() }


/**
 * Takes the vanished directory a MOVED_TO on [newRelativePath] may be the other half
 * of, or null when none of them can be claimed. A claimed entry is consumed, and the saves noted
 * for it pass to [session] in the same step, so a save the write-back thread drops at any later
 * moment is noted too.
 */
internal fun SafSyncEngine.claimVanishedDirectory(
    newRelativePath: String, now: Long, session: WatchSession, arrivingKey: Any?, arrivingAt: Long? = null,
): String? =
    synchronized(vanishedLock) {
        val source = renameSourceFor(vanishedDirectories, newRelativePath, now, arrivingKey, arrivingAt)
        if (source != null) {
            for (entry in vanishedDirectories.filter { it.relativePath == source }) {
                session.claimedDrops.getOrPut(entry.localPath) { ArrayList() } += droppedSaves.remove(entry.localPath).orEmpty()
            }
            vanishedDirectories.removeAll { it.relativePath == source }
        }
        source
    }
