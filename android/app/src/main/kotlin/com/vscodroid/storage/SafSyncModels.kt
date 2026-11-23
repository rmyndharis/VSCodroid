package com.vscodroid.storage

import android.net.Uri
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

// -- Data Classes --

/**
 * Thrown by [SafSyncEngine.initialSync] when the mirror is taken: another sync of it, or a
 * save, delete or rename still being sent to the provider.
 *
 * Its own type because of when it is raised: before anything of the mirror was touched. A
 * caller can therefore put the folder's watcher back even when the folder that was
 * refused is the one being watched, which a sync that failed part-way through writing
 * must never allow.
 */
class FolderBusyException : IOException("The folder is still syncing; try opening it again")

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
    val relativePath: String = "",
    /**
     * Whether [localPath] was a directory, as its event said, which a DELETE has no other
     * way to know by the time it runs: the entry is already gone. Filled by
     * [SafSyncEngine.handleMirrorEvent].
     */
    val isDirectory: Boolean = false,
    /** The mirror and generation observed before resolving this event's provider IDs. */
    val mirrorRoot: File? = null,
    val generation: Any? = null,
    /** Writes and creates queued under the old directory name before this rename claimed it. */
    val renamedSaves: List<String> = emptyList(),
    /** The full previous mirror path whose provider state moves only after relocation. */
    val previousPath: String? = null,
    /**
     * Whether the device documents this job names are looked up when it runs rather than when its event arrived: the
     * event came while a rename of its path, or of a folder above it, was still queued, so the device held them, if at
     * all, under another name. Set for a DELETE and for a RENAME's source; see [SafSyncEngine.resolvedAtRun].
     */
    val resolveAtRun: Boolean = false,
    /**
     * Strictly increasing enqueue order within a [WatchSession], assigned when the job enters
     * [WatchSession.queue]. Used when checking whether a later job supersedes an earlier one,
     * avoiding same-millisecond timestamp ties.
     */
    val seq: Long = 0L,
)

internal enum class SyncType {
    MODIFY, CREATE, DELETE, RENAME
}

/**
 * What became of a device document that a newer mirror copy is about to replace.
 *
 * See [SafSyncEngine.setAsideDeviceCopy] for why the callers need four answers
 * rather than a Boolean.
 */
internal enum class DeviceCopyOutcome {
    /**
     * A `.device-<time>` file is beside the mirror copy, and phase 2b or the live watcher
     * may put it across.
     */
    PRESERVED,

    /**
     * Both sides held the same bytes, so the fetched copy was deleted again.
     *
     * The ordinary end of a delivered write-back rather than an exotic case: the record
     * never learns the time that write left on the device, and a provider with coarse
     * stamps reports one below the mirror's, so every reopen after a save arrives here.
     */
    IDENTICAL,

    /**
     * A writer is streaming into this device document, or began writing during
     * the read, so the copy cannot establish stable bytes. Callers defer the write
     * without marking the document unread, even if the other writer has finished.
     */
    IN_FLIGHT,

    /**
     * The device copy could not be brought over, so writing over it would lose it: the
     * document would not open, the copy failed, another sync is already fetching it, or
     * it is reported larger than [SafSyncEngine.MAX_FILE_SIZE], or holds more than that
     * where the provider did not answer for its size.
     */
    UNAVAILABLE,
}

/** One directory's watch while an open fills the mirror; see [SafSyncEngine.ChangesDuringOpen]. */
internal interface OpenWatch {
    fun start()
    fun stop()
}

/**
 * What [SafSyncEngine] last knew of one device document: the (COLUMN_LAST_MODIFIED,
 * COLUMN_SIZE) its provider reported, or a placeholder no report equals where a read matched
 * the bytes at a stamp query the provider did not answer, and the SHA-256 of what the open
 * fetched from it or this app's last write streamed into it, or of the mirror copy the open
 * kept, with the length of those bytes. That digest is null for a kept copy that no open has
 * digested yet and this open's budget, [SafSyncEngine.KEPT_COPY_DIGEST_BYTES], did not
 * reach, until a write of this app lands there, and after a write that does not land.
 */
internal class DeviceState(
    /**
     * Null where an open found the device holding bytes it could not place, or where a stamp
     * moved past the bytes this engine read and a read could not show them unchanged, so
     * that no stamp the provider reports, or fails to report, lets a save or a delete through
     * without the bytes being read. The digest is then the previous record's for the path,
     * or the one the entry carried when the stamp moved, or null where there is none.
     */
    val stamp: Pair<Long, Long>?,
    val sha256: ByteArray? = null,
    val length: Long = 0,
)

/**
 * A save [SafSyncEngine] held back: the document it was going to, when it is next tried,
 * and how long it waited for that try.
 */
internal class HeldBackSave(val docUri: Uri, val dueAt: Long, val wait: Long)

internal sealed interface DocumentCopyResult

/** The installed copy's filesystem time and the number of bytes fetched. */
internal class LandedCopy(val lastModified: Long, val length: Long) : DocumentCopyResult

internal enum class FailedCopy : DocumentCopyResult { IN_FLIGHT, UNAVAILABLE }

internal class DocumentAccess {
    var writer: String? = null
    var generation = 0L
    var readers = 0
}

internal class ProviderMutation(val key: String?) {
    /** A waiter has already given up on this holder, so the next one need not wait for it. */
    @Volatile var waitedOut = false
}

internal class DocumentWriteDuringReadException : IOException()

/**
 * What [SafSyncEngine.uploadPlan] found under a directory, and whether the cap stopped
 * the walk short of the tree.
 *
 * The flag is carried rather than derived because the two part company at exactly the
 * cap: [entries] is capped at the limit, so its size reads as "there was more" for a
 * directory that ends there naturally as readily as for one with a thousand more files
 * below.
 */
internal data class UploadPlan(val entries: List<File>, val truncated: Boolean)

/**
 * A directory that left the mirror and has not been accounted for yet.
 *
 * [relativePath] is where it was, which is all a later MOVED_TO needs to find the
 * document still standing under that name on the device.
 */
internal data class VanishedDirectory(
    val relativePath: String,
    val atMillis: Long,
    /**
     * Where it was in the mirror, absolutely, which is the only spelling the upload
     * journal answers to: its lines are absolute paths because one engine serves every
     * folder in turn, while [relativePath] is what the device side needs. Carried on the
     * record rather than rebuilt from a mirror root, because the readers that retire
     * those lines ([SafSyncEngine.dropVanished]'s callers) run where no root is in hand.
     */
    val localPath: String,
    /**
     * Its identity ([java.nio.file.attribute.BasicFileAttributes.fileKey]), as recorded when its
     * watch was placed ([SafSyncEngine.watchedDirectoryKeys]); null for one that had no watch.
     * What [SafSyncEngine.renameSourceFor] compares with the arriving directory's.
     */
    val directoryKey: Any? = null,
    /**
     * When the open's watch saw it leave, for a departure the watcher replays from what an open recorded
     * ([SafSyncEngine.ChangesDuringOpen.Recorded.at]); null for one the watcher saw itself.
     */
    val recordedAt: Long? = null,
)

/**
 * One write-back's claim on the mirror path it is streaming into, held from the moment
 * its journal line is written until that write ends.
 *
 * [path] is a var because the directory above it can be renamed in the editor while the
 * stream runs, and the claim and the line are two records of one write:
 * [SafSyncEngine.renameUploadsUnder] moves both together, so the writer's release names
 * the line as it is spelled then rather than as it was spelled when the stream opened.
 * Identity is what a writer releases, so this is deliberately not a data class: two
 * writers of one path hold two claims and each must retire only its own.
 *
 * Read and written only under the journal lock, which is what makes a plain var safe here.
 *
 * [nothingSentBefore] is whether no write under the journal line had been handed a provider
 * stream before this one ([SafSyncEngine.markUnwritten]).
 */
internal class UploadClaim(var path: String, val nothingSentBefore: Boolean = false)

internal data class InboundScratchKey(val parent: Any, val name: String)

internal class OutboundPath(
    val parent: SecureDirectoryStream<Path>,
    val leaf: Path,
    val directories: List<SecureDirectoryStream<Path>>,
    val rootKey: Any,
) : Closeable {
    fun attributes(name: Path = leaf): BasicFileAttributes = parent.getFileAttributeView(
        name, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS,
    )?.readAttributes() ?: throw IOException("Source attributes are unavailable")

    fun parentKey(): Any = parent.getFileAttributeView(BasicFileAttributeView::class.java)
        ?.readAttributes()?.fileKey() ?: throw IOException("The directory has no identity")

    fun sibling(name: String): Path = leaf.fileSystem.getPath(name).also {
        if (it.isAbsolute || it.nameCount != 1 || name == "." || name == "..") {
            throw IOException("The entry name is not a path segment")
        }
    }

    fun input(name: Path = leaf): InputStream = Channels.newInputStream(parent.newByteChannel(
        name, setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
    ))

    fun modified(time: Long) {
        val view = parent.getFileAttributeView(leaf, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            ?: throw IOException("The entry attributes are unavailable")
        view.setTimes(FileTime.fromMillis(time), null, null)
    }

    override fun close() = closeOutboundResources(null, directories)
}

internal class OutboundSource(
    val input: InputStream,
    val attributes: BasicFileAttributes,
    val path: OutboundPath,
) : Closeable {
    override fun close() = closeOutboundResources(input, path.directories)
}

internal fun closeOutboundResources(input: InputStream?, directories: List<SecureDirectoryStream<Path>>) {
    var failure: Exception? = null
    val resources = listOfNotNull<Closeable>(input) + directories.asReversed()
    for (resource in resources) {
        try {
            resource.close()
        } catch (closing: Exception) {
            if (failure == null) failure = closing else failure.addSuppressed(closing)
        }
    }
    failure?.let { throw it }
}

/**
 * Reset and provider mutations share admission. A reset never waits for a provider; a write
 * waits for another write, for a bounded time, and for nothing else.
 */
internal class MirrorAccess {
    val lock = ReentrantLock()
    val stateLock = Any()
    var syncing = false
    @Volatile var generation: Any = Any()
}

internal class OutboundAccess(
    private val mirror: MirrorAccess,
    val generation: Any,
    private val turnWaitMs: Long,
) {
    private fun current() = synchronized(mirror.stateLock) {
        !mirror.syncing && mirror.generation === generation
    }

    /**
     * Runs [action] with the mirror to itself, or answers null when it must not run.
     *
     * Null means stale, and it is said at once for a write admitted under an older
     * generation and for a mirror being reset: neither has anything left to tell the
     * device, and the generation is read before the lock so such a write never contends
     * with the current one. Another current write holding the mirror is a turn to wait
     * for, not staleness: a folder switch leaves the closing drain finishing its queue
     * while the restored watcher's drain starts, and both are entitled to write. The wait
     * is polled, so a reset that begins meanwhile ends it, and bounded by [turnWaitMs], so
     * a provider call that never returns costs the waiting save its place and a notice
     * rather than a thread for good.
     *
     * An interrupt does not cut the wait short: the job has been taken off its queue and a
     * thrown wait would lose it. It is set aside
     * for the action, which must not meet it (a stream copy fails on a thread carrying
     * one), and handed back after.
     *
     * [giveBack] runs last, with the turn still held, once [action] has run or has been refused
     * as stale. It does not run when the turn was never obtained; the caller that took what
     * [giveBack] gives back releases it anyway in that case.
     */
    fun <T> run(giveBack: (() -> Unit)? = null, action: () -> T): T? {
        if (!current()) return null
        var interrupted = false
        try {
            var held = mirror.lock.tryLock()
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(turnWaitMs)
            while (!held) {
                val left = deadline - System.nanoTime()
                if (left <= 0 || !current()) return null
                held = try {
                    mirror.lock.tryLock(minOf(left, TURN_POLL_NANOS), TimeUnit.NANOSECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                    false
                }
            }
            try {
                if (Thread.interrupted()) interrupted = true
                return if (current()) action() else null
            } finally {
                // While the turn is still held: whoever waits for it is woken by the unlock.
                try { giveBack?.invoke() } finally { mirror.lock.unlock() }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    fun <T> update(action: () -> T): T? = synchronized(mirror.stateLock) {
        if (!mirror.syncing && mirror.generation === generation) action() else null
    }

    private companion object {
        /** How often a waiting write looks again at whether a reset has overtaken it. */
        val TURN_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(200)
    }
}

/**
 * One folder's write-back: the jobs its observers produced, and the thread sending them.
 *
 * Per folder rather than per engine, and that is what keeps two writers off one
 * document. [SafSyncEngine.stopWatching] leaves a drain that outran its grace period
 * holding this object and installs a fresh one, so the jobs of the folder opened next go
 * somewhere the departing thread cannot reach, and the thread started for that folder
 * polls a queue nothing else polls.
 */
internal class WatchSession(
    /**
     * The mirror this session's observers watch, whose held-back saves its loop tries
     * again; null for a session no watcher serves.
     */
    val root: File? = null,
    val generation: Any? = null,
) {
    val queue = ConcurrentLinkedQueue<SyncJob>()

    private val nextSeq = AtomicLong(0L)

    /**
     * The live watcher's jobs while what the open recorded is being replayed, which then go into [queue] behind the
     * replay's own; null while none is ([SafSyncEngine.replayChangesDuringOpen]). The watcher starts before the replay
     * has queued anything, and a save in a folder the replay makes or renames, queued ahead of that folder's job, found
     * no folder on the device: it was announced as kept in the app, and a rename did not carry it. Guarded by [queue].
     */
    private var heldForReplay: MutableList<SyncJob>? = null

    fun holdLiveJobs() = synchronized(queue) { if (heldForReplay == null) heldForReplay = ArrayList() }

    fun releaseLiveJobs() = synchronized(queue) {
        heldForReplay?.forEach { queue.offer(it.copy(seq = nextSeq.incrementAndGet())) }
        heldForReplay = null
    }

    /** Queues [job], or holds it while a replay runs when it is the [live] watcher's. */
    fun offer(job: SyncJob, live: Boolean) {
        synchronized(queue) {
            val held = heldForReplay
            if (live && held != null) held += job else queue.offer(job.copy(seq = nextSeq.incrementAndGet()))
        }
    }

    /** What is queued and what is held, in the order it will be sent. */
    fun pending(): List<SyncJob> = synchronized(queue) { queue.toList() + heldForReplay.orEmpty() }

    /**
     * Takes every queued or held job [follow] answers for out of its place and queues the answer behind everything,
     * as [offer] with [live] would. The job being sent is left as it is.
     */
    fun requeue(live: Boolean, follow: (SyncJob) -> SyncJob?) = synchronized(queue) {
        val moved = ArrayList<SyncJob>()
        for (jobs in listOfNotNull<MutableCollection<SyncJob>>(queue, heldForReplay)) {
            val each = jobs.iterator()
            while (each.hasNext()) follow(each.next())?.let { each.remove(); moved += it }
        }
        moved.forEach { offer(it, live) }
    }

    /**
     * The job the write-back thread has taken off [queue] and not finished, set under the same lock as the poll, so a
     * job not yet finished is always in one of the two. Guarded by [queue].
     */
    private var inFlight: SyncJob? = null

    fun take(): SyncJob? = synchronized(queue) { queue.poll().also { inFlight = it } }

    fun finished() = synchronized(queue) { inFlight = null }

    /** [pending] and the job being sent, first; see [SafSyncEngine.renameQueuedOver]. */
    fun underway(): List<SyncJob> = synchronized(queue) { listOfNotNull(inFlight) + queue.toList() + heldForReplay.orEmpty() }

    /** One-shot inode checks for directory names already followed after provider creation or rename. */
    val normalizedDirectories = ConcurrentHashMap<String, Pair<String, Any>>()

    /**
     * The new name of each rename that gave way to a later rename onto it, with the path it left, by absolute path:
     * what a delete looked up when it runs finds at the new name is still at the old one on the device
     * ([SafSyncEngine.resolvedAtRun]). Only while a rename onto the name is still queued and nothing has been made
     * again there: a folder moved out of the mirror queues nothing, and a delete of what was made at its name since
     * is not one of the folder the rename left. Read and written by the write-back thread.
     */
    val gaveWay = ConcurrentHashMap<String, String>()

    /**
     * The saves and creates the write-back thread dropped for a file under a directory that a rename
     * has claimed, by the directory's old absolute path, until the rename's job takes them, which it
     * does first, whether it then runs or gives way to a later rename. Guarded by the engine's vanished
     * lock. Per session because the job that takes them runs on this session's thread, and what no job
     * takes goes with the session.
     */
    val claimedDrops = HashMap<String, MutableList<String>>()

    /**
     * Saves and creates the write-back thread dropped for a file that was not at its path while no
     * departed or claimed directory held it, each with when, by absolute path. Guarded by the engine's
     * vanished lock. A directory's move lands on disk before its MOVED_FROM is reported, so a save of
     * a file in it can be taken in between with nothing to be noted with yet: the MOVED_FROM takes the
     * ones under it ([SafSyncEngine.handleMirrorEvent]), and the rest expire after
     * [SafSyncEngine.RENAME_PAIR_WINDOW_MS], as a departure no rename claims does, so the save of a file
     * that was simply deleted is not sent with a move that comes later.
     */
    val recentDrops = ArrayList<Pair<String, Long>>()

    /** Cleared by [SafSyncEngine.stopWatching]; the loop's own termination condition. */
    @Volatile var running = true

    /**
     * Whether [queue] has stopped being polled, which no session ever comes back from:
     * a worker is started once, by the [SafSyncEngine.startWatching] that installed the
     * session, and never again.
     *
     * What it buys is a notice to the user and a sentence in a bug report. An event
     * observed while the folder was open can finish its provider round trips after the
     * drain has taken its last look, and the save it carries then reaches no device and
     * nothing retries it. The stop's own count cannot see that one: on an ordinary folder
     * switch the idle drain exits in microseconds, so the queue is counted and cleared
     * while the event is still inside the provider, and the offer that follows is what has
     * to say so.
     *
     * True from birth for the session [SafSyncEngine.stopWatching] installs, which is the
     * one no worker is ever started for. An event that read [SafSyncEngine.isWatching]
     * before that stop and the session after it is handed exactly that object, and its
     * save is as undelivered as the one above.
     */
    @Volatile var abandoned = false

    /** The thread draining [queue], or null before one is started and once it is joined. */
    @Volatile var worker: Thread? = null

    /** Whether an inotify queue overflow occurred during this watch session. */
    @Volatile var overflowed = false

    /** Ensures at most one asynchronous overflow reconciliation is queued or running per burst. */
    val overflowPending = AtomicBoolean(false)

    private val observerDispatchLock = Any()
    private var observerDispatcher: ThreadPoolExecutor? = null
    private var observerDispatchClosed = false
    private val pendingOverflowAction = AtomicReference<(() -> Unit)?>(null)

    private fun getOrCreateObserverDispatcher(capacity: Int): ThreadPoolExecutor? =
        synchronized(observerDispatchLock) {
            if (observerDispatchClosed) return null
            observerDispatcher ?: ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                ArrayBlockingQueue(capacity.coerceAtLeast(1)),
            ) { runnable ->
                Thread(runnable, "saf-observer-dispatch").apply { isDaemon = true }
            }.also { observerDispatcher = it }
        }

    private fun drainPendingOverflow() {
        if (synchronized(observerDispatchLock) { observerDispatchClosed }) return
        pendingOverflowAction.getAndSet(null)?.invoke()
    }

    /**
     * Submits a live observer callback onto this session's bounded single-thread executor.
     *
     * Returns true when accepted or when this session is already closing, and false only when
     * the bounded queue is full so the caller can trigger overflow reconciliation.
     */
    fun dispatchObserverEvent(capacity: Int, task: () -> Unit): Boolean {
        val executor = getOrCreateObserverDispatcher(capacity) ?: return true
        return try {
            executor.execute {
                try {
                    task()
                } finally {
                    if (executor.queue.isEmpty()) drainPendingOverflow()
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            synchronized(observerDispatchLock) { observerDispatchClosed }
        }
    }

    /**
     * Schedules overflow reconciliation on this session's single-thread executor.
     *
     * Stores [action] before submitting so even if the bounded queue is full and submission is
     * rejected, the last queued task drains [pendingOverflowAction] once the queue empties.
     */
    fun scheduleObserverOverflow(capacity: Int, action: () -> Unit) {
        val executor = getOrCreateObserverDispatcher(capacity) ?: return
        pendingOverflowAction.set(action)
        try {
            executor.execute { drainPendingOverflow() }
        } catch (_: RejectedExecutionException) {
            // Queue is either full (the tail task drains pendingOverflowAction) or closing.
        }
    }

    /**
     * Stops accepting new observer callbacks for this session and waits up to [graceMs] for
     * already queued callbacks to finish. Forces shutdown and detaches if stalled past [graceMs].
     */
    fun drainObserverDispatcher(graceMs: Long) {
        val executor = synchronized(observerDispatchLock) {
            observerDispatchClosed = true
            pendingOverflowAction.set(null)
            observerDispatcher?.also { it.shutdown() }
        } ?: return
        val drained = try {
            executor.awaitTermination(graceMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!drained) {
            executor.shutdownNow()
        }
        synchronized(observerDispatchLock) {
            if (observerDispatcher === executor) observerDispatcher = null
        }
    }
}

internal class InboundStage(
    val parent: SecureDirectoryStream<Path>,
    val name: Path,
    private val key: InboundScratchKey,
    val output: OutputStream,
) : Closeable {
    var moved = false

    fun attributes(): BasicFileAttributes = parent.getFileAttributeView(
        name, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS,
    )?.readAttributes() ?: throw IOException("Staging attributes are unavailable")

    fun input(): InputStream = Channels.newInputStream(parent.newByteChannel(
        name, setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
    ))

    fun modified(time: Long) {
        parent.getFileAttributeView(name, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            ?.setTimes(FileTime.fromMillis(time), null, null)
    }

    override fun close() {
        runCatching { output.close() }
        synchronized(SafSyncEngine.inboundScratchPaths) {
            try {
                if (!moved) runCatching { parent.deleteFile(name) }
            } finally {
                SafSyncEngine.inboundScratchPaths.remove(key)
                runCatching { parent.close() }
            }
        }
    }
}

internal data class InboundParent(val fileKey: Any)

internal class InboundEntry(val attributes: BasicFileAttributes, val digest: ByteArray?) {
    fun matches(other: InboundEntry): Boolean =
        sameInboundAttributes(attributes, other.attributes) &&
            if (digest == null) other.digest == null else
                other.digest != null && digest.contentEquals(other.digest)
}

internal class CreatedDocument(val uri: Uri, val localFile: File, val delivered: Boolean)

/** A direct child of a device directory: its id, and whether it is a directory when known. */
internal class ChildDocument(val docId: String, val isDirectory: Boolean?)
