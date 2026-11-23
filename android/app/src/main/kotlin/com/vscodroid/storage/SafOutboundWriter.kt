package com.vscodroid.storage

import android.net.Uri
import android.os.FileObserver
import android.os.SystemClock
import android.provider.DocumentsContract
import com.vscodroid.util.Logger
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.PushbackInputStream
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread
import com.vscodroid.storage.SafSyncEngine.Companion.COPY_BUFFER_SIZE
import com.vscodroid.storage.SafSyncEngine.Companion.WRITEBACK_POLL_MS
import com.vscodroid.storage.SafSyncEngine.Companion.HELD_BACK_RETRY_FIRST_MS
import com.vscodroid.storage.SafSyncEngine.Companion.HELD_BACK_RETRY_MAX_MS
import com.vscodroid.storage.SafSyncEngine.Companion.uploadJournalLock
import com.vscodroid.storage.SafSyncEngine.Companion.documentAccess
import com.vscodroid.storage.SafSyncEngine.Companion.claimProviderMutation
import com.vscodroid.storage.SafSyncEngine.Companion.claimProviderMutationWithin
import com.vscodroid.storage.SafSyncEngine.Companion.ownsProviderMutation
import com.vscodroid.storage.SafSyncEngine.Companion.releaseProviderMutation
import com.vscodroid.storage.SafSyncEngine.Companion.documentWriteKey
import com.vscodroid.storage.SafSyncEngine.Companion.anotherMirrorWritesDocument
import com.vscodroid.storage.SafSyncEngine.Companion.withStableDocumentRead
import com.vscodroid.storage.SafSyncEngine.Companion.SYNCED_RECORD_SUFFIX
import com.vscodroid.storage.SafSyncEngine.Companion.RECORD_HEADER
import com.vscodroid.storage.SafSyncEngine.Companion.splitRecordLine
import com.vscodroid.storage.SafSyncEngine.Companion.unescapeRecordPath
import com.vscodroid.storage.SafSyncEngine.Companion.supersededByLaterJob
import com.vscodroid.storage.SafSyncEngine.Companion.parentPathOf
import com.vscodroid.storage.SafSyncEngine.Companion.MAX_WATCHED_DIRECTORIES
import com.vscodroid.storage.SafSyncEngine.Companion.MAX_FILE_SIZE
import com.vscodroid.storage.SafSyncEngine.Companion.UNANSWERED_SIZE
import com.vscodroid.storage.SafSyncEngine.Companion.MAX_UPLOAD_ENTRIES
import com.vscodroid.storage.SafSyncEngine.Companion.SKIP_DIRECTORIES
import com.vscodroid.storage.SafSyncEngine.Companion.isSafeSegment
import com.vscodroid.storage.SafSyncEngine.Companion.uploadPlan
import com.vscodroid.storage.SafSyncEngine.Companion.sha256
import com.vscodroid.storage.SafSyncEngine.Companion.readFully
import com.vscodroid.storage.SafSyncEngine.Companion.isLink
import com.vscodroid.storage.SafSyncEngine.Companion.watchableDirectories

// Outbound mirror-to-SAF write-back loop, validation, and held-back retry helpers for [SafSyncEngine].

/**
 * Preserves device bytes that do not match the last delivered or imported digest.
 * Missing, equal, and changed metadata all require byte proof before replacement.
 * An unavailable preservation copy holds the save for [retryHeldBack]; unknown
 * documents are preserved when [preserveUnknown] is requested.
 *
 * Comparison reads at most the baseline length and one buffer. Preservation follows
 * the ordinary copy policy: reported oversize documents are refused, unknown sizes
 * are copied whole, and unanswered metadata uses [MAX_FILE_SIZE]. A document changed
 * by another app after preservation but before the write remains a provider race.
 */
internal fun SafSyncEngine.keepsDeviceEdit(
    localFile: File,
    docUri: Uri,
    mirrorRoot: File,
    access: OutboundAccess? = null,
    preserveUnknown: Boolean = false,
): Boolean {
    if (withCurrentMemory(access) { true } != true) {
        announceLost(localFile)
        return true
    }
    // Settled again by whatever this answers: a save that goes ahead ends the hold, and
    // one held back once more waits longer before it is tried again.
    val held = withCurrentMemory(access) {
        val removed = heldBack.remove(localFile.absolutePath)
        if (removed != null) {
            settledHeldWait[localFile.absolutePath] = removed.wait
        } else {
            settledHeldWait.remove(localFile.absolutePath)
        }
        removed
    }
    val kept = if (anotherMirrorWritesDocument(docUri, mirrorRoot)) {
        DeviceCopyOutcome.IN_FLIGHT
    } else {
        val now = deviceMovedPast(localFile, docUri, access) ?: if (
            preserveUnknown && !deviceSeen.containsKey(localFile.absolutePath)
        ) {
            deviceStamp(docUri) ?: (0L to UNANSWERED_SIZE)
        } else return false
        // A write of this app's own that was cut short left its journal line standing, and the device document holds the leading bytes of the mirror file and nothing
        // else. Nothing is kept by setting those aside: every byte of them is in the file about to be written. The line alone proves nothing about what touched the document
        // since, so the bytes are read, as the first open does it for the same line (deviceIsTruncatedMirror). A line whose writes never handed the provider a stream
        // stands for no bytes of this app's (partlyWrittenUploads), and a size that is 0 or unanswered rules nothing out.
        if (localFile.absolutePath in partlyWrittenUploads() && deviceIsTruncatedMirror(docUri, localFile, mirrorRoot, now.second)) return false
        setAsideDeviceCopy(
            DocumentInfo(docUri, "", localFile.name, false, now.second, now.first),
            localFile,
            mirrorRoot,
            access,
        )
    }
    if (kept == DeviceCopyOutcome.PRESERVED || kept == DeviceCopyOutcome.IDENTICAL) return false
    Logger.w(
        tag,
        if (kept == DeviceCopyOutcome.IN_FLIGHT) {
            "Not writing ${localFile.name} back: its device copy was involved in another write"
        } else {
            "Not writing ${localFile.name} back: its device copy changed since this app " +
                "last read or wrote it and could not be set aside"
        },
    )
    val wait =
        held?.let { minOf(it.wait * 2, HELD_BACK_RETRY_MAX_MS) } ?: HELD_BACK_RETRY_FIRST_MS
    withCurrentMemory(access) {
        settledHeldWait.remove(localFile.absolutePath)
        announceLost(localFile)
        heldBack[localFile.absolutePath] =
            HeldBackSave(docUri, SystemClock.elapsedRealtime() + wait, wait)
    }
    return true
}


/** Returns metadata for a version not proved equal to the last known device bytes. */
internal fun SafSyncEngine.deviceMovedPast(localFile: File, docUri: Uri, access: OutboundAccess? = null): Pair<Long, Long>? {
    val seen = deviceSeen[localFile.absolutePath] ?: return null
    // A missing or unchanged timestamp cannot distinguish another application's bytes.
    val now = deviceStamp(docUri) ?: (0L to UNANSWERED_SIZE)
    // Read at any length a set-aside could still keep, a missing size column's 0 and an
    // unanswered query's length included: a reported length can lag the bytes, as on a
    // provider that reports a write's new time before its new length, and skipping the
    // read there kept a copy of the previous save. Past [MAX_FILE_SIZE] only at the
    // digested length, because a document another app grew that far was read in full at
    // every save and each save was held back anyway.
    if (seen.sha256 != null &&
        (now.second == seen.length || now.second <= MAX_FILE_SIZE) &&
        seen.sha256.contentEquals(deviceSha256(docUri, seen.length))
    ) {
        withCurrentMemory(access) {
            deviceSeen.replace(localFile.absolutePath, seen, DeviceState(now, seen.sha256, seen.length))
        }
        return null
    }
    // The device now holds what this engine has not read, and the stamp it compared
    // against no longer says anything: kept, it let a held-back save's try, the next
    // save, a delete, or a file made again after a declined delete go over that version
    // unread once a stamp query went unanswered, or once the provider reported the old
    // stamp again. So no stamp, as for a copy an open could not place, and only the
    // bytes answer from here. Only mark the snapshot read above: a reset or completed
    // write may have replaced it during provider I/O, and owns that newer state.
    withCurrentMemory(access) {
        deviceSeen.replace(localFile.absolutePath, seen, DeviceState(null, seen.sha256, seen.length))
    }
    return now
}


/**
 * Declines the delete [job] would send to [docUri], and says so, where the device holds
 * there what this engine has not read: a document, or for a directory one under it, the
 * folder's last open could not read, or a file another app changed since this engine
 * last read or wrote it.
 *
 * [handleMirrorEvent] asks the first before it queues the delete, and a job can outlive
 * that answer: a reopen while the closing folder's drain is still inside a slow provider
 * call can find the document unreadable, and the delete queued before it then removed a
 * document nothing had read. The second is [keepsDeviceEdit]'s question, which no delete
 * asked, so an edit another app made while the folder was open went with the file. The
 * bytes decide it as they do for a save, so this app's own write settling late does not
 * hold a delete back, and a read that fails does. Declined rather than set aside as a
 * save's device copy is: the user deleted the file, and a copy beside it would bring it
 * back under another name. The device keeps the document, and the next open brings it
 * back into the editor.
 *
 * A directory also checks its current descendants through [directoryDeleteIsSafe],
 * because the provider's recursive delete includes children that raised no local
 * event. A declined delete is not retried; the next open brings its device copy back.
 */
internal fun SafSyncEngine.keepsDeviceDocument(job: SyncJob, docUri: Uri, access: OutboundAccess? = null): Boolean {
    val unread = holdsUnread(job.localPath, job.isDirectory)
    val held = (unread || job.isDirectory && holdsKept(job.localPath)) &&
        job.safTreeUri?.let { providerHoldsImpl(it, job.relativePath) } != false
    if (!held) {
        val seen = deviceSeen[job.localPath]
        val unchanged = if (job.isDirectory) directoryDeleteIsSafe(job, docUri) else
            seen?.sha256 != null && seen.sha256.contentEquals(deviceSha256(docUri, seen.length))
        if (unchanged) {
            return false
        }
        withCurrentMemory(access) {
            keptOnDevice.add(job.localPath)
            if (!job.isDirectory && seen != null) {
                deviceSeen.replace(job.localPath, seen, DeviceState(null, seen.sha256, seen.length))
            }
        }
    }
    // Said as a change rather than as content the editor never had, for every file this
    // declines: the delete passed [handleMirrorEvent]'s guard, so the editor had the file,
    // and what the device keeps is a version that moved past what this engine read, or
    // one an open after the delete could not read. A directory is kept for what is unread
    // under it where there is any, and otherwise for such a file.
    keepUnread(
        File(job.localPath), job.relativePath, job.isDirectory,
        changed = !job.isDirectory || !unread,
    )
    return true
}


/**
 * A recursive provider delete may remove children the mirror never saw. Ask at the
 * drain, without updating the cache that identifies the documents already known.
 * Each file must still hold its recorded bytes, including where its stamp did not
 * move. Provider changes after this check remain outside the SAF delete contract.
 */
internal fun SafSyncEngine.directoryDeleteIsSafe(job: SyncJob, docUri: Uri): Boolean {
    val treeUri = job.safTreeUri ?: return false
    val knownIds = synchronized(docIdCache) {
        if (cacheTree != treeUri) return false
        docIdCache.toMap()
    }
    val root = File(job.localPath)
    val states = deviceSeen.toMap()
    try {
        val directoryId = DocumentsContract.getDocumentId(docUri)
        if (directoryId.isNullOrEmpty() || knownIds[job.relativePath] != directoryId) return false
        if (documentIsDirectory(docUri) != true) return false
        val pending = ArrayDeque<Pair<String, String>>()
        pending.add(directoryId to job.relativePath)
        val visited = mutableSetOf(directoryId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        while (pending.isNotEmpty()) {
            val (parentId, parentPath) = pending.removeFirst()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                cursor.extras?.let { extras ->
                    if (extras.getBoolean(DocumentsContract.EXTRA_LOADING) ||
                        extras.containsKey(DocumentsContract.EXTRA_ERROR)
                    ) return false
                }
                val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idIndex)
                    val name = cursor.getString(nameIndex)
                    val mime = cursor.getString(mimeIndex)
                    if (id.isNullOrEmpty() || !isSafeSegment(name) || mime.isNullOrEmpty()) return false
                    val path = "$parentPath/$name"
                    if (knownIds[path] != id || !visited.add(id)) return false
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        pending.add(id to path)
                    } else {
                        val local = File(root, path.removePrefix("${job.relativePath}/"))
                        val seen = states[local.absolutePath] ?: return false
                        val digest = seen.sha256 ?: return false
                        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                        if (!digest.contentEquals(deviceSha256(uri, seen.length))) return false
                    }
                }
            } ?: return false
        }
    } catch (e: Exception) {
        Logger.w(tag, "Could not verify directory contents before deletion: ${e.javaClass.simpleName}")
        return false
    }
    return true
}


/**
 * Tries again, as the save it was, one save [keepsDeviceEdit], [writeLocalToSaf], or an
 * open arm held back under [session]'s mirror whose wait is over, and answers whether it
 * found one.
 *
 * The write-back loop asks whenever its queue is empty, so a held-back save lands once
 * its device copy can be read, still by [keepsDeviceEdit]'s comparison of the bytes, and
 * the waits bound what a provider that stays unreadable costs: per held-back file, a
 * stamp query and the reads that fail, each as far as it gets, so a set-aside's fetch
 * that breaks off partway streams that far again at every try, even past [MAX_FILE_SIZE]
 * where the provider reports no size; or, while the provider does not answer that query
 * over a device copy past [MAX_FILE_SIZE], a read of the digested length and a buffer and
 * one of that limit, after [HELD_BACK_RETRY_FIRST_MS] and then at waits that double up to
 * [HELD_BACK_RETRY_MAX_MS]. One per call, because the loop polls its queue before it asks
 * again: tried all at once, a save made meanwhile waited behind every try that was due,
 * which on a server that takes long to refuse a read is long for a save that has nothing
 * to do with them. A file the mirror no longer holds is dropped rather than tried, which
 * would spin the loop on a save with nothing left to send, and so would a mirror this
 * session may no longer write to (a reset is running, or a newer open took it over): a
 * try there is refused without ending the hold, so it answers false and the loop parks.
 * The dropped one is noted for the rename of a directory it may have left with
 * ([rememberDroppedSave]), whose job then sends it at the new name. A try runs as a save
 * does, so one of a file the folder's last open could not read is refused like a save of it.
 */
internal fun SafSyncEngine.retryHeldBackImpl(session: WatchSession): Boolean {
    val root = session.root ?: return false
    val access = outboundAccess(root, session.generation)
    if (withCurrentMemory(access) { true } == null) return false
    val prefix = root.absolutePath + File.separator
    val now = SystemClock.elapsedRealtime()
    for ((path, held) in heldBack) {
        if (!path.startsWith(prefix) || held.dueAt > now) continue
        if (!File(path).isFile) {
            heldBack.remove(path, held)
            rememberDroppedSave(session, path)
            continue
        }
        // Sent here rather than queued, and stamped older than any job an observer
        // queues, so the debounce drops the try where a job of the file has arrived
        // since the loop found its queue empty, which sends what the try would. Queued,
        // the try went behind such a job with a later time: a create of the file was
        // dropped for it, and where another app had deleted the document the hold
        // names, the try failed there and the file reached no document at all.
        processWriteBackImpl(
            session, SyncJob(
                SyncType.MODIFY, path, held.docUri, null, null, Long.MIN_VALUE,
                mirrorRoot = session.root,
                generation = access.generation,
                seq = Long.MIN_VALUE,
            ),
        )
        return true
    }
    return false
}


/**
 * The same loop bound to one folder's [session], which is the form the watcher's own
 * thread runs: a drain that outlives [stopWatching] goes on polling the queue it was
 * started with, and the folder opened next has a different one.
 */
internal fun SafSyncEngine.runWriteBackLoopImpl(session: WatchSession, isRunning: () -> Boolean) {
    var interrupted = false
    try {
        while (isRunning()) {
            if (Thread.interrupted()) {
                interrupted = true
                break
            }
            val job = session.take()
            if (job != null) {
                try { processWriteBackImpl(session, job) } finally { session.finished() }
            } else if (!retryHeldBack(session)) {
                // Parked, not slept: a stop wakes it with unpark. An interrupt lands in whatever the
                // thread is doing, and in the middle of a save the next read of the local file fails
                // on it, leaving the device document cut short.
                LockSupport.parkNanos(WRITEBACK_POLL_MS * 1_000_000L)
            }
        }
    } catch (_: InterruptedException) {
        // An interrupt from outside, which wakes a sleeping thread and ends the loop
        // the way a stop does, is not a fault. Letting it escape reaches the thread's
        // uncaught handler, and Android's default handler ends the process: closing a
        // folder, which happens on every folder switch and on destroy, would close the app.
        interrupted = true
    }
    // Whichever way the loop ended, the queue can still hold writes the user is
    // expecting on the device, and they have to go out with the interrupt status
    // clear: the stream copies below throw InterruptedIOException on a thread still
    // carrying the flag, losing exactly what this drain exists to save.
    if (Thread.interrupted()) interrupted = true

    var remaining = session.take()
    while (remaining != null) {
        try { processWriteBackImpl(session, remaining) } finally { session.finished() }
        remaining = session.take()
    }
    // Said after the last poll rather than before it, so the flag is never set while
    // this thread could still deliver: an event that reads it answers "nothing will
    // take this now" only when that is already true.
    session.abandoned = true
    if (interrupted) Thread.currentThread().interrupt()
}


/**
 * Whether the device document is exactly what this app's own interrupted
 * write-back leaves behind: a STRICT PREFIX of the mirror.
 *
 * The upload journal records that a write started and did not finish. It does
 * not record that nothing touched the document afterwards, and the two are
 * only the same thing if the app was the last writer. `"wt"` truncates at
 * open and the copy runs forward from zero, so a write cut short leaves the
 * document holding the mirror's leading bytes and nothing else. Any other
 * content, longer, shorter with different bytes, or the same length, was
 * written by something that is not that interrupted write, and the caller
 * must preserve it rather than overwrite it.
 *
 * Strict, because equal length is [deviceMatchesMirror]'s question and means
 * the write finished after all. Every case is decided from the two streams:
 *
 * | Device document | Answer |
 * |---|---|
 * | unreadable | false; the caller preserves it before replacing |
 * | empty, mirror is not | true, the empty prefix |
 * | empty, mirror is empty too | false, not strict |
 * | shorter, bytes agree | true |
 * | shorter, bytes differ | false |
 * | same length, or longer | false |
 * | the stream ends before a positive [reportedSize] | false |
 *
 * The last row is asked of the stream after it ended, never before it is read: a stream that
 * ends before the size the provider reports holds part of the document, and a part that agreed
 * with the mirror passed for this app's own cut-short write, so the write went over another
 * app's edit. A size of 0, which is also what a provider that omits it reports, or one that
 * was not answered, rules nothing out, for the reason below.
 *
 * ⚠️ A length pre-check must not come back. `COLUMN_SIZE` is optional, a NULL reads back
 * as 0 through the cursor, and `walkTree` does not test for it, so a provider
 * that omits it claims 0 for every document it returns. The pre-check this
 * replaced, `deviceSize <= 0L || deviceSize >= localFile.length()`, therefore
 * answered false for every file of such a provider, and for every genuinely
 * empty document as well: which is exactly what a `"wt"` open followed by an
 * immediate death leaves, the most likely shape of the write this exists to
 * recognise. A false here sends the EXPECTED case into [setAsideDeviceCopy],
 * which keeps a `.device-` copy of this app's own half-written bytes, and
 * phase 2b then puts that copy in the user's folder, where nothing removes
 * it. The relation is a property of two byte streams and both of them are
 * open here; a third party's claim about one of them is asked only to rule a
 * prefix out once the stream has ended short of it.
 *
 * The cost is one provider read of at most `min(document, mirror) + 1` bytes,
 * paid a second time by [setAsideDeviceCopy] wherever this answers false.
 * Affordable only because a journal line gates the branch, so it never runs
 * on a healthy folder. [deviceMatchesMirror] decides the other way and says
 * why: on a folder with no clock it runs at every open for each file the
 * record does not vouch for, and wherever the free-space floor holds a fetch
 * back.
 *
 * A read that fails answers false, the safe direction: the caller then
 * preserves the document before replacing it.
 */
internal fun SafSyncEngine.deviceIsTruncatedMirror(safDocUri: Uri, localFile: File, mirrorRoot: File, reportedSize: Long): Boolean {
    return try {
        openOutboundSource(localFile, mirrorRoot).use { selected ->
            val matched = withStableDocumentRead(safDocUri) {
                context.contentResolver.openInputStream(safDocUri)?.use { device ->
                    selected.input.use { mirror ->
                        val a = ByteArray(COPY_BUFFER_SIZE)
                        val b = ByteArray(COPY_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val read = readFully(device, a, a.size)
                            // The document ended while the two still agreed, so it is
                            // a prefix. Strict needs one more byte in the mirror, and
                            // it is asked of the stream rather than of
                            // `localFile.length()`: the mirror is a file an editor may
                            // be writing while this runs, and a second measurement of
                            // it can disagree with the bytes just read. Unless the
                            // stream ended short of the size the provider reports.
                            if (read == 0) return@use (reportedSize <= 0 || total >= reportedSize) && mirror.read() != -1
                            total += read
                            // The mirror ran out first, so the document holds more
                            // than any prefix of it: something else put it there.
                            if (readFully(mirror, b, read) != read) return@use false
                            if (!java.util.Arrays.equals(a, 0, read, b, 0, read)) return@use false
                        }
                        @Suppress("UNREACHABLE_CODE") false
                    }
                } ?: false
            }
            matched && sameInboundAttributes(selected.attributes, selected.path.attributes()) &&
                parentIsCurrent(selected.path, localFile, mirrorRoot)
        }
    } catch (e: Exception) {
        Logger.w(
            tag,
            "Could not compare ${localFile.name} with its device copy: " +
                e.javaClass.simpleName,
        )
        false
    }
}


/**
 * Whether the device document and the mirror hold the same bytes.
 *
 * The one question that can be answered without a clock, and the only one this
 * path needs. When the watcher has already carried an edit to the device, the two
 * agree in content while the record still describes what was there before, and
 * that mismatch is what froze the file: nothing else ever brings the bookkeeping
 * back into step. Phase 2 also asks it of a fetch the free-space floor held back,
 * where a read spends no disk and the fetch would have.
 *
 * Reads the device document, so lengths are compared first: unequal lengths cannot
 * hold equal bytes, and skipping the read there is what keeps this off the common
 * path.
 *
 * ⚠️ That length is the provider's claim, not a measurement, and `COLUMN_SIZE` is
 * optional in the same way `COLUMN_LAST_MODIFIED` is. A provider omitting it reports
 * every length as 0, which is why 0 rules nothing out and the bytes decide: answered
 * false for every non-empty file, a provider omitting both never had a file tracked
 * again, and a save delivered there would read as a device copy unlike the mirror's,
 * of which the next save keeps a spare copy. The read stops a buffer past the mirror's
 * length. With no clock phase 2 asks only about the files the record does not vouch
 * for, while each file it does vouch for is fetched in full on every open, and below
 * the free-space floor the read stands in for such a fetch. A reported length still
 * rules the read out. [deviceIsTruncatedMirror] takes the opposite decision, and says
 * why.
 *
 * A read that fails answers false. That is the safe direction: false leaves the
 * mirror kept and vouches for nothing new, while a true this could not justify would
 * vouch for bytes nobody compared. After a fetch the floor held back, a false still
 * lets phase 2 carry the line the previous record held for a copy nobody has touched
 * since, which says no more than it said before.
 */
internal fun SafSyncEngine.deviceMatchesMirror(safDocUri: Uri, localFile: File, deviceSize: Long, selected: OutboundSource): Boolean {
    if (deviceSize != 0L && deviceSize != selected.attributes.size()) return false
    return try {
        withStableDocumentRead(safDocUri) {
            context.contentResolver.openInputStream(safDocUri)?.use { device ->
                selected.input.use { mirror ->
                    val a = ByteArray(COPY_BUFFER_SIZE)
                    val b = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        val read = readFully(device, a, a.size)
                        val same = readFully(mirror, b, b.size)
                        if (read != same) return@use false
                        if (read == 0) return@use true
                        // Only the bytes this chunk filled. Comparing the whole buffer
                        // would drag in the previous, longer chunk's tail.
                        if (!java.util.Arrays.equals(a, 0, read, b, 0, read)) return@use false
                    }
                    @Suppress("UNREACHABLE_CODE") false
                }
            } ?: false
        }
    } catch (e: Exception) {
        Logger.w(
            tag,
            "Could not compare ${localFile.name} with its device copy: " +
                e.javaClass.simpleName,
        )
        false
    }
}


/**
 * [deviceMatchesMirror], answered true only for a mirror file nothing wrote while the
 * two were being read.
 *
 * Both streams are read to their ends, which on a large file or a slow provider takes
 * a while, and the watcher is stopped for the whole open: an editor's autosave, a
 * terminal or a build can write the file meanwhile, behind the point the read has
 * reached. The comparison then answers for bytes that are gone, and a caller that
 * settles on it records the file as held by the device and, where the device reports
 * a time, stamps it with that older time. The next open reads that as a save already
 * delivered, and the edit is never sent.
 *
 * The file's time and length are taken before the comparison, ahead of its own length
 * check, and again after it. A file that moved is not the one the comparison answered
 * for, and false is what every caller already makes of "the device copy is not known
 * to hold these bytes": the file stays as the editor wrote it and takes the deferral
 * or the failed copy it would have taken had the bytes differed.
 *
 * Ceilings, both inherent in checking a file's identity rather than holding it: a
 * rewrite of the same length inside one tick of the file system's clock shows no
 * change, and a write between the second reading and the caller's stamp is not seen.
 */
internal fun SafSyncEngine.deviceMatchesUnchangedMirror(
    safDocUri: Uri,
    localFile: File,
    deviceSize: Long,
    mirrorRoot: File,
    onMatched: (OutboundPath, BasicFileAttributes) -> Unit = { _, _ -> },
): Boolean {
    if (anotherMirrorWritesDocument(safDocUri, mirrorRoot)) return false
    return try {
        openOutboundSource(localFile, mirrorRoot).use { selected ->
            if (!deviceMatchesMirror(safDocUri, localFile, deviceSize, selected)) return@use false
            val after = selected.path.attributes()
            if (!sameInboundAttributes(selected.attributes, after) ||
                !parentIsCurrent(selected.path, localFile, mirrorRoot)
            ) return@use false
            onMatched(selected.path, after)
            true
        }
    } catch (_: Exception) {
        false
    }
}


/**
 * Writes a local file's contents back to its corresponding SAF document.
 *
 * Declines outright when another write already holds the document, so two streams
 * never interleave into one file: see [documentAccess]. Declines a symbolic
 * link outright too, for the reason spelled out at that test.
 *
 * The copy itself runs in [writeLocalToSafHoldingDocument], bracketed by the
 * in-flight journal, and the bracket is the whole point of that method's shape:
 * `"wt"` truncates the document at open, and the provider stamps a fresh
 * modification time on the shorter bytes, so a copy
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
internal fun SafSyncEngine.writeLocalToSafImpl(
    localFile: File, safDocUri: Uri, mirrorRoot: File, mutation: ProviderMutation,
): Boolean {
    if (!ownsProviderMutation(safDocUri, mutation)) {
        deferWriteBack(localFile, safDocUri)
        return false
    }
    // A symbolic link is never written out, which is the same refusal [uploadPlan]
    // and [watchableDirectories] make, in the same words and for the same reason: a
    // mirror is routinely a checked-out repository, so a link inside one is
    // attacker-supplied in the ordinary case. Every stream below reads *through* the
    // link, so without this a link named `notes.txt` puts its target's bytes into the
    // device document of that name, and the target can be anywhere this app can read,
    // its own private storage included.
    //
    // Here rather than at the four call sites, because this is the one point all four
    // pass through and two of them had no link test anywhere: a CREATE or MODIFY the
    // observer reports for a single entry never goes near [uploadPlan], which is the
    // only place the tree stated this policy. A DELETE does not come through here,
    // which is what it needs: by then the entry is gone, the link question has no
    // answer, and the device document still has to be removed.
    if (isLink(localFile)) {
        Logger.w(
            tag,
            "Not writing ${localFile.name} back: it is a symbolic link, and writing " +
                "it would copy its target's bytes into the device folder",
        )
        return false
    }
    val document = documentWriteKey(safDocUri)
    val claimed = synchronized(documentAccess) {
        val state = documentAccess.getOrPut(document) { DocumentAccess() }
        if (state.writer != null) false else {
            state.writer = mirrorRoot.absolutePath
            state.generation++
            true
        }
    }
    if (!claimed) {
        // Someone is streaming into this document now. Declining is what keeps the
        // two from interleaving, and holding the save lets the write-back loop try
        // again once that stream finishes.
        Logger.d(tag, "Another write holds ${localFile.name}; leaving it to that one")
        deferWriteBack(localFile, safDocUri)
        return false
    }
    settledHeldWait.remove(localFile.absolutePath)
    try {
        return writeLocalToSafHoldingDocument(localFile, safDocUri, mirrorRoot)
    } finally {
        synchronized(documentAccess) {
            val state = documentAccess.getValue(document)
            state.writer = null
            if (state.readers == 0) documentAccess.remove(document)
        }
    }
}


internal fun SafSyncEngine.deferWriteBack(localFile: File, docUri: Uri) {
    val settledWait = settledHeldWait.remove(localFile.absolutePath)
    val wait = (heldBack[localFile.absolutePath]?.wait ?: settledWait)
        ?.let { minOf(it * 2, HELD_BACK_RETRY_MAX_MS) }
        ?: HELD_BACK_RETRY_FIRST_MS
    announceLost(localFile)
    heldBack[localFile.absolutePath] = HeldBackSave(
        docUri, SystemClock.elapsedRealtime() + wait, wait,
    )
}


/** The copy itself, with the document already claimed by the caller. */
internal fun SafSyncEngine.writeLocalToSafHoldingDocument(localFile: File, safDocUri: Uri, mirrorRoot: File): Boolean {
    val claim = markUploadInFlightImpl(localFile.absolutePath) ?: run {
        onWriteBackFailed(localFile)
        return false
    }
    // What a hold was waiting to send goes out now, as the file stands, so the hold ends
    // here whatever this write comes to: one that fails is reported and kept in the
    // journal like any other. Nearly every write comes after [keepsDeviceEdit], which
    // has settled the hold already, but a create writes a document it has just made
    // without asking it. A hold left standing there named the document before, which
    // another app may have deleted: its next try reported this save as lost, kept a
    // journal line that refuses the mirror's reclaim, and dropped what the new document
    // was read as, so the save after it went over another app's edit of that document
    // unguarded.
    heldBack.remove(localFile.absolutePath)
    // Released in a finally rather than at each exit, and this is not style.
    // The claim taken above was originally dropped at the one exit its author
    // had in mind, and the failure exits added later each returned without it,
    // so the count never came back to zero for the life of the process. From
    // then on [clearUploadInFlight] saw a writer that no longer existed and
    // kept the journal line after a write-back that had landed, and
    // [consumeStaleUploadRecord] refused to consume it, so every later
    // initialSync force-pushed the mirror over whatever the device held. A
    // device edit made in between was destroyed, silently, on every reopen.
    //
    // The distinction the two paths draw is what the journal means, and it is
    // the reason there are two calls rather than one. The line records an edit
    // that has not reached the device, so a landed write retires it and a
    // failed one leaves it standing for the next sync to retry. The one thing
    // that leaves nothing to retry is the mirror file going away while this
    // write ran, and the failing path retires the line for that alone; see
    // [releaseUploadClaim].
    var landed = false
    // Digested on the way out, so it is exactly what the provider was handed whatever
    // the mirror file holds by the time [keepsDeviceEdit] asks.
    var written: ByteArray? = null
    var writtenLength = 0L
    var providerAttempted = false
    // Whether any try was handed a provider stream: until one is, nothing of this write is on the device.
    var outputOpened = false
    try {
        var attempts = 0
        while (true) {
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val (copied, observed) = openOutboundSource(localFile, mirrorRoot).use { source ->
                    // A refused or unreadable source must not truncate the document.
                    providerAttempted = true
                    val count = context.contentResolver.openOutputStream(safDocUri, "wt")
                        ?.use { output ->
                            outputOpened = true
                            DigestInputStream(source.input, digest).copyTo(output, COPY_BUFFER_SIZE)
                        }
                    count to source.attributes
                }
                if (copied != null) {
                    landed = true
                    written = digest.digest()
                    writtenLength = copied
                    try {
                        val delivered = File(synchronized(uploadJournalLock) { claim.path })
                        openOutboundPath(delivered, mirrorRoot).use { destination ->
                            val after = inboundEntry(destination)
                            if (after != null && sameInboundAttributes(observed, after.attributes) &&
                                copied == after.attributes.size() && written.contentEquals(after.digest)
                            ) {
                                requireCurrentParent(destination, delivered, mirrorRoot)
                                recordDeliveredCopyImpl(
                                    delivered, mirrorRoot, after.attributes.lastModifiedTime().toMillis(), copied, written,
                                )
                            }
                        }
                    } catch (_: Exception) {
                        // A changed or unreadable mirror copy remains unvouched.
                    }
                    return true
                }
                attempts++
            } catch (e: SecurityException) {
                Logger.e(tag, "Permission revoked while writing back: ${localFile.name}")
                onWriteBackFailed(localFile)
                return false
            } catch (e: Exception) {
                attempts++
            }
            if (attempts >= 2) {
                Logger.e(
                    tag,
                    "Write-back of ${localFile.name} did not finish; its device copy " +
                        "is recorded as not to be trusted"
                )
                onWriteBackFailed(localFile)
                return false
            }
        }
    } finally {
        // By the claim rather than by the path this opened with: a rename of the
        // directory above it moves both records while the stream runs.
        if (landed) clearUploadInFlight(claim)
        else releaseUploadClaimImpl(claim, wroteNothing = !outputOpened)
        // The version a declined delete kept there is replaced once these bytes land.
        if (landed) keptOnDevice.remove(localFile.absolutePath)
        // An attempted output open may leave this app's bytes, whole or truncated.
        // A refused source leaves the previous device baseline in place instead.
        // A write cut short has no digest because its prefix is unknown.
        if (providerAttempted) {
            val stamp = deviceStamp(safDocUri)
            if (outputOpened) {
                deviceSeen[localFile.absolutePath] = DeviceState(stamp, written, writtenLength)
            } else {
                val seen = deviceSeen[localFile.absolutePath]
                val stampMoved = stamp != null && seen?.stamp != null && stamp != seen.stamp
                if (seen == null || stampMoved) {
                    deviceSeen[localFile.absolutePath] = DeviceState(stamp, null, 0L)
                } else if (stamp == null && seen.stamp != null) {
                    deviceSeen.replace(localFile.absolutePath, seen, DeviceState(null, seen.sha256, seen.length))
                }
            }
        }
    }
}


/** Opens a descendant's parent relative to the captured mirror without following links. */
internal fun SafSyncEngine.openOutboundPathImpl(localFile: File, mirrorRoot: File): OutboundPath {
    val root = mirrorRoot.toPath().toAbsolutePath().normalize()
    val file = localFile.toPath().toAbsolutePath().normalize()
    if (file == root || !file.startsWith(root)) throw IOException("Source is outside its mirror")
    val parent = root.parent ?: throw IOException("Mirror has no parent")
    val relative = root.relativize(file)
    val directories = mutableListOf<SecureDirectoryStream<Path>>()
    try {
        // The app-owned mirror parent may itself be reached through Android's
        // filesDir alias. Every component below that anchor is opened without links.
        val opened = Files.newDirectoryStream(parent)
        val anchor = opened as? SecureDirectoryStream<Path>
        if (anchor == null) {
            opened.close()
            throw IOException("Filesystem cannot confine a mirror source")
        }
        directories.add(anchor)
        var directory = anchor.newDirectoryStream(root.fileName, LinkOption.NOFOLLOW_LINKS)
        directories.add(directory)
        val rootKey = directory.getFileAttributeView(BasicFileAttributeView::class.java)
            ?.readAttributes()?.fileKey() ?: throw IOException("The mirror has no identity")
        anchor.close()
        directories.removeAt(0)
        for (index in 0 until relative.nameCount - 1) {
            val previous = directory
            directory = previous.newDirectoryStream(relative.getName(index), LinkOption.NOFOLLOW_LINKS)
            directories.add(directory)
            previous.close()
            directories.removeAt(0)
        }
        return OutboundPath(directory, relative.fileName, directories, rootKey)
    } catch (failure: Exception) {
        try {
            closeOutboundResources(null, directories)
        } catch (closing: Exception) {
            failure.addSuppressed(closing)
        }
        throw failure
    }
}


/** Opens and tests a regular source before a provider can truncate its destination. */
internal fun SafSyncEngine.openOutboundSource(localFile: File, mirrorRoot: File): OutboundSource {
    val path = openOutboundPath(localFile, mirrorRoot)
    var input: InputStream? = null
    try {
        val attributes = path.attributes()
        if (!attributes.isRegularFile) throw IOException("Source is not a regular file")
        val channel = path.parent.newByteChannel(
            path.leaf, setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
        )
        val readable = PushbackInputStream(Channels.newInputStream(channel), 1)
        input = readable
        // A channel can open for an entry that cannot be read. Test that before wt.
        val first = readable.read()
        if (first >= 0) readable.unread(first)
        return OutboundSource(readable, attributes, path)
    } catch (failure: Exception) {
        try {
            closeOutboundResources(input, path.directories)
        } catch (closing: Exception) {
            failure.addSuppressed(closing)
        }
        throw failure
    }
}


internal fun SafSyncEngine.validateOutboundDirectory(localFile: File, mirrorRoot: File) {
    openOutboundPath(localFile, mirrorRoot).use { path ->
        if (!path.attributes().isDirectory) throw IOException("Source is not a directory")
    }
}


internal fun SafSyncEngine.processWriteBackImpl(session: WatchSession, queued: SyncJob) {
    writeBackSession.set(session)
    try {
        processWriteBackJob(session, queued)
    } finally {
        writeBackSession.remove()
    }
}


internal fun SafSyncEngine.processWriteBackJob(session: WatchSession, queued: SyncJob) {
    val mirrorRoot = queued.mirrorRoot ?: session.root
    val access = mirrorRoot?.let {
        outboundAccess(it, queued.generation ?: session.generation)
    }
    // A rename's dropped saves leave the session before the debounce below can skip the job: one
    // that gives way to a later rename onto its name has nowhere to send them, and left behind
    // they were sent again by the next rename of the same old name.
    val claimedSaves = if (queued.type == SyncType.RENAME) {
        queued.previousPath?.let { takeClaimedDrops(session, it) }.orEmpty()
    } else emptyList()
    // Small debounce: skip a job that a more recent one for the same path makes
    // pointless, see [supersededByLaterJob]. Scoped to the session the job came off,
    // so it cannot see, or be blinded by, the jobs of a folder that is closing or one
    // that has just opened.
    //
    // A rename is not given way to a later rename onto its name when a rename queued
    // between the two takes its result away from that name, as `mv old tmp; mv tmp
    // final; mv other tmp` inside one poll does: the two then never meet on the device,
    // and the one between finds its source there only once this one has run. Given
    // way, the device kept `old`, and `final` was made again from the copy without
    // what the copy never held.
    // Something made again at the name a rename gave way to stands there now, and a delete of the name after it is
    // of that, not of the folder the rename left; see [WatchSession.gaveWay].
    if (queued.type == SyncType.CREATE || queued.type == SyncType.MODIFY) session.gaveWay.remove(queued.localPath)
    val pending = session.queue.toList()
    if (pending.withIndex().any { (index, later) ->
            later.localPath == queued.localPath &&
                (queued.safTreeUri == null || later.safTreeUri == null || later.safTreeUri == queued.safTreeUri) &&
                later.seq > queued.seq &&
                supersededByLaterJob(queued, later) &&
                !(queued.type == SyncType.RENAME && pending.subList(0, index).any { renamesAway(it, queued.localPath) })
        }
    ) {
        // A delete queued between the two, as in `mv old tmp; rm -r tmp; mv other tmp`, removes what this one
        // would have brought to the name, which the device holds under the old one: it is looked up there
        // ([resolvedAtRun]). Looked up at the new name, it found nothing, and the device kept `old` for the next
        // open to bring back. Sending this rename instead would leave the folder at the name wherever a delete of
        // it is refused, and put the later rename beside it under a name the provider makes up.
        if (queued.type == SyncType.RENAME) queued.previousPath?.let { session.gaveWay[queued.localPath] = it }
        return
    }
    if (queued.type == SyncType.RENAME) session.gaveWay.remove(queued.localPath)
    val job = if (queued.resolveAtRun) resolvedAtRun(queued, access, session) else queued

    Logger.d(tag, "Write-back: ${job.type} ${job.localPath}")

    when (job.type) {
        SyncType.MODIFY -> {
            val localFile = File(job.localPath)
            if (!localFile.exists()) {
                rememberDroppedSave(session, job.localPath)
                return
            }
            if (job.safDocUri != null) {
                // [handleMirrorEvent]'s unread-document guard again, where the write
                // happens, because a job can outlive the answer it was queued under. A
                // reopen while the closing drain is still inside a slow provider read
                // can find the device copy unreadable and leave the listed stamp as
                // what [keepsDeviceEdit] compares against, so a save queued before
                // the reopen, or the hold that read leaves once it fails, went over a
                // document nothing had read. A [retryHeldBack] job never passes that
                // guard at all. Asked as the delete's recheck asks it, through
                // [providerHolds], so only a device that answers "gone" lets the save
                // through: a stamp query asked instead read an unanswered query as no
                // document, and the save went over the one the reopen could not read. A
                // try carries no tree to walk, so one of such a file is refused. The
                // hold ends with the refusal, or the loop would try it again on every
                // idle turn. This and the delete's recheck in [keepsDeviceDocument] ask
                // this engine's own [unfetched]. Generation admission below also rejects
                // a drain whose mirror was reopened by a replacement engine.
                if (holdsUnread(job.localPath, isDirectory = false) &&
                    writeWouldReplaceUnreadDocument(
                        job.localPath,
                        job.safTreeUri?.let { providerHoldsImpl(it, job.relativePath) } != false,
                    )
                ) {
                    heldBack.remove(job.localPath)
                    settledHeldWait.remove(job.localPath)
                    refuseUnreadDocument(localFile, localFile.name)
                } else if (mirrorRoot == null) {
                    announceLost(localFile)
                } else if (withCurrent(access) { true } == null) {
                    // Wait for a previous mutation without holding admission over provider reads.
                    announceLost(localFile)
                } else {
                    val mutation = claimProviderMutation(job.safDocUri)
                    if (mutation == null) {
                        deferWriteBack(localFile, job.safDocUri)
                    } else try {
                        if (!keepsDeviceEdit(localFile, job.safDocUri, mirrorRoot, access, preserveUnknown = true)) {
                            // The claim goes back before the turn does; see [withCurrent].
                            val written = withCurrent(access, { releaseProviderMutation(mutation) }) {
                                writeLocalToSafImpl(localFile, job.safDocUri, mirrorRoot, mutation)
                            }
                            if (written == null) announceLost(localFile)
                        }
                    } finally {
                        releaseProviderMutation(mutation)
                    }
                }
            } else if (job.safTreeUri != null) {
                // FileObserver may report MODIFY instead of CREATE for new files
                // (e.g., `echo > file` on Android API 36). Fall through to CREATE.
                Logger.d(tag, "MODIFY on unknown file, treating as CREATE: ${localFile.name}")
                createInQueuedParent(job, localFile, access, mirrorRoot)
            }
        }
        SyncType.CREATE -> {
            val localFile = File(job.localPath)
            if (localFile.exists()) createInQueuedParent(job, localFile, access, mirrorRoot)
            else rememberDroppedSave(session, job.localPath)
        }
        SyncType.DELETE -> {
            // A null uri is NOT the same outcome as a refusal, though it used to
            // share the branch: [findChildDocId] folds a failed provider query into
            // the null it returns for genuine absence, so "could not be resolved"
            // and "is not there" arrive here as one answer. Only the first leaves
            // the device holding what the editor no longer shows; for the second
            // both clauses of the notice are false, and it tells the user to go and
            // delete a file that is not there.
            //
            // [providerHolds] is the three-valued form of the same question and is
            // what the DELETE guard above already uses. Announce unless the device
            // positively answers "gone": a provider that cannot answer is still
            // treated as holding, so a transient failure keeps the warning it used
            // to give.
            //
            // A rename queued ahead of this job is the other way a document that was
            // missing when the event arrived is there by now: after `mv a b && rm -r b`
            // in one poll the rename runs first, and the children of `b` exist on the
            // device under that name. Such a job is looked up again when it runs only
            // where this engine read what the rename carried there, so the delete goes
            // through [keepsDeviceDocument]'s comparison of the bytes ([resolvedAtRun]).
            // Anywhere else it is reported, not looked up by path and deleted: a deletion
            // cannot be undone, and a lookup at the drain finds whatever sits at the path
            // by then, which nothing here can tell from the document the event named.
            var replacedLocally = false
            var busyRefusal = false
            val refused = when {
                job.safDocUri == null ->
                    job.safTreeUri != null &&
                        providerHoldsImpl(job.safTreeUri, job.relativePath) != false
                else -> {
                    // The claim before the turn and given back inside it, as a save and a
                    // rename take theirs; see [withCurrent]. Taken inside the turn, a delete
                    // held the mirror for the whole claim wait while a save of the same
                    // mirror held the claim and waited for that turn, which two drains of
                    // one mirror do after a busy reopen. A stale drain is let go first, so
                    // it does not wait for a claim it would never use.
                    val mutation = if (withCurrent(access) { true } == null) null
                        else claimProviderMutationWithin(job.safDocUri, providerClaimWaitMs)
                            .also { if (it == null) busyRefusal = true }
                    val handled = if (mutation == null) {
                        if (busyRefusal) false else null
                    } else try {
                        withCurrent(access, { releaseProviderMutation(mutation) }) {
                            val current = File(job.localPath)
                            val currentKind = when {
                                current.isDirectory -> true
                                current.isFile -> false
                                else -> null
                            }
                            replacedLocally = !isLink(current) && currentKind != null &&
                                (currentKind == job.isDirectory ||
                                    documentIsDirectory(job.safDocUri) == currentKind)
                            val alreadyGone = !replacedLocally && job.isDirectory && job.safTreeUri != null &&
                                providerHoldsImpl(job.safTreeUri, job.relativePath) == false
                            replacedLocally || alreadyGone || keepsDeviceDocument(job, job.safDocUri, access) ||
                                deleteFromSafImpl(job.safDocUri)
                        }
                    } finally {
                        releaseProviderMutation(mutation)
                    }
                    if (handled == null) {
                        keepUnread(File(job.localPath), job.relativePath, job.isDirectory, changed = true)
                    }
                    handled == false
                }
            }
            if (replacedLocally) return
            if (busyRefusal) {
                announceDeleteBusy(File(job.localPath))
            } else if (refused) {
                announceDeleteRefused(File(job.localPath))
            }
            // Both cleanups below run whatever the device folder decided.
            // The mirror entry is already unlinked, so what they remove is
            // stale either way, and skipping them on a refusal would leave
            // the reclaim reporting the copy for ever as holding work the
            // device does not have.
            //
            // The enqueue resolved that path to the document it was about to
            // remove, and the resolution is cached. Left behind, the entry
            // answers for a name the device no longer holds: the next event
            // on a recreated path writes into the dead document, and a rename
            // into that name is refused its pair. Nothing else removes these,
            // so the delete that strands them is the delete that clears them.
            withCurrentMemory(access) {
                if (!busyRefusal && job.relativePath.isNotEmpty() && job.safTreeUri != null) {
                    forgetCachedSubtree(job.safTreeUri, job.relativePath)
                }
                // And the upload record, for the same reason and with the same
                // reach. A journal line is keyed by the mirror path and is removed
                // only by a write of that path that lands, so a write-back that gave
                // up left one standing, deliberately, and nothing ever retired it
                // once the file it names went away. Since a file rename arrives here
                // as MOVED_FROM, one failed save followed by renaming the file in the
                // editor was enough to strand a journal line for a path that no
                // longer exists. A later initial sync reads that journal.
                // The claims are what keep this off a path somebody is streaming
                // into right now; see [consumeStaleUploadRecord]. The path the event named, also
                // for a delete looked up under the name a rename that gave way left ([resolvedAtRun]).
                if (!File(queued.localPath).isFile) consumeStaleUploadRecordImpl(queued.localPath)
                // A save held back for this path has nothing left to send, and a file
                // made again under the name is a new document that its own create
                // writes, which ends a hold too. Left standing until then, the hold was
                // tried against the deleted document whenever the loop found its queue
                // empty before the create's event arrived, which on a provider whose
                // ids are not paths fails and reports the new file's save as lost.
                heldBack.remove(job.localPath)
                settledHeldWait.remove(job.localPath)
            }
        }
        SyncType.RENAME -> {
            // safDocUri is the document under the *old* name here, and localPath the
            // new one. Falling back to a create when the provider will not rename is
            // not a nicety: without it a provider lacking FLAG_SUPPORTS_RENAME would
            // leave the new name absent from the device entirely, which is worse than
            // the duplicate this job exists to avoid.
            //
            // A rename that succeeds does not re-upload what the device already holds,
            // and it should not: the watcher has been writing every change in that
            // subtree back as it happened, so re-sending it would be a file-by-file
            // rewrite of a copy that is already correct, 1500 provider writes for a
            // renamed `src/`, with every save queued behind them. It sends what the
            // record does not vouch for under the new name instead ([notVouchedUnder]):
            // what was made or saved in the folder while no watch of it ran, between
            // the rename on disk and the start of the watch of its new name, and with
            // it a changed file whose directory fell past [MAX_WATCHED_DIRECTORIES],
            // which no observer reported.
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
            // Every save this thread took off the queue before it came to this job is noted by now, so the
            // ones it dropped for the old directory, taken above, are added to the ones queued when the job
            // was offered: their files moved with the directory, and the replay writes whatever the mirror
            // holds at the new path.
            val lateSaves = job.previousPath?.let { previous ->
                claimedSaves.map { job.relativePath + "/" + it.removePrefix(previous + File.separator) }
            }.orEmpty()
            val renamedSaves = (job.renamedSaves + lateSaves).distinct()
            val localFile = File(job.localPath)
            if (withCurrent(access) { true } == null) {
                announceDroppedRename(localFile, renamedSaves, mirrorRoot)
            } else {
                // Acquire the provider claim outside [mirror.lock] so a slow provider
                // claim wait cannot hold the mirror turn lock or invert lock order
                // against queued file saves.
                val mutation = job.safDocUri?.let { claimProviderMutationWithin(it, providerClaimWaitMs) }
                if (mutation == null) {
                    announceDroppedRename(localFile, renamedSaves, mirrorRoot)
                } else try {
                    // Looked up once the claim is held rather than before its wait, for the reason
                    // above: a destination another write was making while the wait ran is there by
                    // now, and an answer taken before the wait dropped the move with a notice.
                    val targetParentUri = job.safParentUri
                        ?: if (job.safSourceParentUri != null && job.relativePath.isNotEmpty()) {
                            // Only while the cache still belongs to this job's tree. A
                            // drain can outlive its folder, and a cache refilled for the
                            // next one answers a relative path with the wrong folder's
                            // documents; declining costs the documented stale-copy
                            // fallback, which the pre-existing behaviour was anyway.
                            job.safTreeUri?.takeIf { it == cacheTree }?.let {
                                resolveDocumentUriImpl(it, File(job.relativePath).parent ?: "", access)
                            }
                        } else {
                            null
                        }
                    // The claim goes back before the turn does; see [withCurrent].
                    if (withCurrent(access, { releaseProviderMutation(mutation) }) {
                        val moved = when {
                            job.safSourceParentUri != null && targetParentUri != null ->
                                moveInSaf(job.safDocUri, job.safSourceParentUri, targetParentUri)
                            // A rename inside one directory has nothing to move. This arm is
                            // only that case: [handleMirrorEvent] does not offer a RENAME at
                            // all when the pair crossed directories and the source parent
                            // could not be resolved, because renaming in the parent the
                            // document is already in is not the move that was asked for.
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
                        val renamed = moved?.let {
                            if (nameUnchanged) it else renameInSaf(it, localFile.name)
                        }
                        // Whether the document is now where the user put it, under whatever
                        // name. The second arm of the `when` above hands back the document that
                        // never moved, so a non-null [moved] on its own does not mean a move
                        // happened; only the first arm calls `moveDocument`, and it is the arm
                        // that requires a source parent.
                        val relocated = moved != null && job.safSourceParentUri != null
                        val storedUri = renamed ?: moved?.takeIf { relocated }
                        if (storedUri != null) {
                            // A save held for a document under the old name goes to the new one with the rest:
                            // the replay finds the document there by name and asks [keepsDeviceEdit] again, so
                            // the URI the hold captured, which a provider whose ids are paths hands to whatever
                            // is made at the old path next, is never written through.
                            val heldSaves = job.previousPath?.let { previous ->
                                val below = previous + File.separator
                                val held = heldBack.keys.filter { it == previous || it.startsWith(below) }
                                held.forEach { heldBack.remove(it) }
                                settledHeldWait.keys.removeAll { it == previous || it.startsWith(below) }
                                held.filter { it.startsWith(below) }.map { job.relativePath + "/" + it.removePrefix(below) }
                            }.orEmpty()
                            val replay = (renamedSaves + heldSaves).distinct()
                            val storedName = storedDocumentName(storedUri)
                            if (storedName != null && isSafeSegment(storedName)) {
                                val actualFile = File(localFile.parentFile, storedName)
                                val actualMirror = mirrorNamedAsStoredImpl(storedUri, localFile, storedName, mirrorRoot)
                                job.previousPath?.let {
                                    renameDeviceMemoryUnder(File(it), actualFile)
                                }
                                if (job.safTreeUri != null && job.mirrorRoot != null) {
                                    val actualPath = actualFile.relativeTo(job.mirrorRoot).path
                                    forgetCachedSubtree(job.safTreeUri, job.relativePath)
                                    cacheDocId(job.safTreeUri, actualPath, DocumentsContract.getDocumentId(storedUri))
                                    if (actualMirror == actualFile) {
                                        val following = if (actualMirror != localFile) {
                                            session.queue.filter {
                                                (it.type == SyncType.CREATE || it.type == SyncType.MODIFY) &&
                                                    it.localPath.startsWith(localFile.absolutePath + File.separator)
                                            }.map { it.relativePath }
                                        } else emptyList()
                                        val unvouched = notVouchedUnder(session, actualMirror, job)
                                        replayRenamedWrites(session, job, actualMirror, replay + following + unvouched, mutation)
                                    } else {
                                        // The device stored another name and the mirror could not take it too,
                                        // so these stay under the name the person gave, where nothing sends them.
                                        // Only the saves are named: the folder itself is on the device.
                                        announceUnsentSaves(replay, job.mirrorRoot)
                                    }
                                }
                            } else {
                                // The provider moved, but its new path cannot be verified yet.
                                unfetched.add(localFile.parentFile.absolutePath)
                                announceDroppedRename(localFile, replay, mirrorRoot)
                            }
                        }
                        if (renamed == null && !relocated && localFile.exists()) {
                            if (targetParentUri != null && job.safTreeUri != null && mirrorRoot != null) {
                                createInSafImpl(localFile, targetParentUri, job.safTreeUri, mirrorRoot, mutation = mutation)
                            } else {
                                announceDroppedRename(localFile, renamedSaves, mirrorRoot)
                            }
                        } else if (renamed == null && relocated) {
                            // The move landed and the rename did not, which a provider carrying
                            // FLAG_SUPPORTS_MOVE and not FLAG_SUPPORTS_RENAME produces. The
                            // create fallback is the wrong answer here and used to run anyway:
                            // the device would keep the whole moved subtree under its OLD name
                            // in the NEW parent and gain a second copy under the new name, built
                            // from the mirror, capped at [MAX_UPLOAD_ENTRIES] and missing
                            // everything [SKIP_DIRECTORIES] kept out. The fallback exists for
                            // the case where nothing moved at all, where the alternative is the
                            // new name being absent from the device entirely. That is not this
                            // case: the content is on the device, whole, in the right place, and
                            // wearing the old name.
                            Logger.w(
                                tag,
                                "Moved ${localFile.name} on the device but could not rename it; " +
                                    "it is in the right folder under its previous name",
                            )
                        }
                    } == null) announceDroppedRename(localFile, renamedSaves, mirrorRoot)
                } finally {
                    releaseProviderMutation(mutation)
                }
            }
        }
    }
}


/**
 * What [dir], a folder a rename has just carried to its new name on the device, holds that the record does not
 * vouch for under that name, as paths for [replayRenamedWrites]: a file whose copy no row names, and a folder no
 * row lies under, which goes whole. The watcher stops the watch of a folder at its departure and starts the watch
 * of its new name at its arrival, so what a program makes or saves in it between the rename on disk and that start
 * raises no event, and the rename carries only what the device already held. Read once the rows have moved with
 * the rename ([renameDeviceMemoryUnder]), and nothing where the record cannot be read, since then nothing tells a
 * copy the device holds from one it lacks. A path a queued job sends, and a folder a rename queued behind this one
 * moves, is left to that job.
 */
internal fun SafSyncEngine.notVouchedUnder(session: WatchSession, dir: File, job: SyncJob): List<String> {
    val root = job.mirrorRoot ?: return emptyList()
    val lines = readSyncedRecord(File(root.path + SYNCED_RECORD_SUFFIX))
    if (lines?.firstOrNull() != RECORD_HEADER) return emptyList()
    val rows = lines.drop(1).mapTo(HashSet()) { splitRecordLine(it).first }
    val folders = HashSet<String>()
    for (row in rows) {
        var folder = parentPathOf(unescapeRecordPath(row.substringBefore('\t')))
        while (folder.isNotEmpty() && folders.add(folder)) folder = parentPathOf(folder)
    }
    val queued = session.queue.toList()
    val queuedPaths = queued.mapTo(HashSet()) { it.localPath }
    val whole = queued.filter { it.type == SyncType.RENAME }.mapTo(ArrayList()) { it.localPath + File.separator }
    val found = ArrayList<String>()
    for (entry in uploadPlan(dir, MAX_UPLOAD_ENTRIES).entries) {
        val path = entry.absolutePath
        if (path in queuedPaths || whole.any { path.startsWith(it) }) continue
        val relative = entry.toRelativeString(root)
        if (if (entry.isDirectory) relative in folders else identityLine(relative, entry) in rows) continue
        if (entry.isDirectory) whole += path + File.separator
        found += job.relativePath + "/" + entry.toRelativeString(dir)
    }
    return found
}


/**
 * [job] with the device documents it names looked up now, for a job whose event came while a rename of its path,
 * or of a folder above it, was still queued ([SyncJob.resolveAtRun]); that rename has run by now, in queue order.
 * A move into a folder made just before it, which follows that folder's rename behind the folder's create, takes
 * the jobs looked up here that wait for it along ([followCreatedFolder]).
 *
 * A delete names its document only where this engine has read what stands at the path under the name that rename
 * carried it to: a file's bytes, or what is under a folder. [keepsDeviceDocument] then checks the device's copy
 * against them, as for any delete. Elsewhere a lookup by path finds whatever stands there by now, so the delete is
 * left as one that could not name its document. Where that rename gave way to a later one onto the same name
 * ([WatchSession.gaveWay]), the delete is of what the device still holds under the name the rename left, and is
 * looked up, and told of, under that name.
 *
 * A rename names its source and, for a move, the folder the source is in. Where the device holds no source, or
 * already holds the new name, the folder is made from the mirror, as a pair that was not claimed is.
 */
internal fun SafSyncEngine.resolvedAtRun(queued: SyncJob, access: OutboundAccess?, session: WatchSession): SyncJob {
    val tree = queued.safTreeUri ?: return queued
    val root = queued.mirrorRoot ?: return queued
    if (queued.type == SyncType.DELETE) {
        val left = session.gaveWay.entries.firstOrNull { (to, _) ->
            (queued.localPath == to || queued.localPath.startsWith(to + File.separator)) &&
                session.pending().any { it.type == SyncType.RENAME && it.localPath == to }
        }
        val job = if (left == null) queued else {
            val source = File(left.value + queued.localPath.removePrefix(left.key))
            queued.copy(localPath = source.absolutePath, relativePath = source.relativeTo(root).path, safParentUri = null)
        }
        val read = if (job.isDirectory) deviceSeen.keys.any { it.startsWith(job.localPath + File.separator) }
            else deviceSeen[job.localPath]?.sha256 != null
        return if (read) job.copy(safDocUri = resolveDocumentUriImpl(tree, job.relativePath, access)) else job
    }
    val job = queued
    if (job.type != SyncType.RENAME) return job
    val from = File(job.previousPath ?: return job).relativeTo(root).path
    val target = job.safParentUri ?: resolveDocumentUriImpl(tree, parentPathOf(job.relativePath), access)
    val source = if (providerHoldsImpl(tree, job.relativePath) == false) resolveDocumentUriImpl(tree, from, access) else null
    val crosses = parentPathOf(from) != parentPathOf(job.relativePath)
    val sourceParent = if (source != null && crosses) resolveDocumentUriImpl(tree, parentPathOf(from), access) else null
    if (source == null || crosses && sourceParent == null) return job.copy(type = SyncType.CREATE, safParentUri = target)
    withCurrentMemory(access) { forgetCachedSubtree(tree, from) }
    return job.copy(safDocUri = source, safParentUri = target, safSourceParentUri = sourceParent)
}


/**
 * Replays queued writes without repeating a file whose upload already completed. A file no longer
 * there has moved on with a later rename of the directory, and is noted for that rename's job.
 */
internal fun SafSyncEngine.replayRenamedWrites(
    session: WatchSession, job: SyncJob, directory: File, paths: List<String>, mutation: ProviderMutation,
) {
    val treeUri = job.safTreeUri ?: return
    val root = job.mirrorRoot ?: return
    val delivered = mutableSetOf<String>()
    for (path in paths.distinct().sortedBy { it.count { character -> character == '/' } }) {
        val suffix = path.removePrefix(job.relativePath + "/")
        val file = File(directory, suffix)
        if (file.absolutePath in delivered) continue
        if (isLink(file)) continue
        if (!file.exists()) {
            rememberDroppedSave(session, file.absolutePath)
            continue
        }
        val parentPath = file.parentFile.relativeTo(root).path
        val parentUri = resolveDocumentUriImpl(treeUri, parentPath)
        if (parentUri == null) {
            announceLost(file)
            continue
        }
        val created = createOneInSaf(file, parentUri, treeUri, root, mutation = mutation)
        if (created == null) announceLost(file)
        else if (created.localFile.isDirectory) {
            createChildrenInSaf(
                created.localFile, created.uri, treeUri, root, delivered = delivered, mutation = mutation,
                movedHere = queuedRenames(session),
            )
        }
    }
}


/**
 * Creates [localFile] in its folder on the device for a job whose event could not
 * name that folder.
 *
 * The event resolved the folder when it arrived, and the job that makes or renames
 * that folder was still ahead of it in the queue, so a folder the device holds by
 * now was not there yet. Dropped on that, a save inside a folder renamed a moment
 * earlier never reached it, and nothing said so.
 *
 * Asking again is not enough on its own, because dropping was also what kept a burst
 * of files in a new folder (a clone, an install) from being sent twice: the folder's
 * own create uploads whatever the mirror holds when it runs and remembers each
 * document it makes, so a path already remembered was carried and has nothing left
 * to do. A renamed folder's children are not remembered, [forgetCachedSubtree]
 * dropped them and the rename does not refill them, so they go through. A folder
 * that is still not on the device leaves the file in the mirror only, and the user
 * is told, while the cache still speaks for the job's tree: a drain can outlive its
 * folder, and then the file may well have been carried by its folder's create, so no
 * notice is given for it. A link is never uploaded, so one queued here has nothing
 * to report.
 */
internal fun SafSyncEngine.createInQueuedParent(job: SyncJob, localFile: File, access: OutboundAccess?, mirrorRoot: File?) {
    val treeUri = job.safTreeUri ?: return
    if (mirrorRoot == null) {
        announceLost(localFile)
        return
    }
    var parentUri = job.safParentUri
    if (parentUri == null) {
        // Built by something other than [handleMirrorEvent]: nothing to ask about.
        if (job.relativePath.isEmpty()) return
        if (treeUri != cacheTree) return
        if (cachedDocId(treeUri, job.relativePath) != null) return
        if (isLink(localFile)) return
        parentUri = resolveDocumentUriImpl(treeUri, File(job.relativePath).parent ?: "", access)
        if (parentUri == null) {
            announceLost(localFile)
            return
        }
    }
    createInSafImpl(localFile, parentUri, treeUri, mirrorRoot, access)
}


/** Where the renames queued in [session] put a folder the device already holds; see [createChildrenInSaf]. */
internal fun SafSyncEngine.queuedRenames(session: WatchSession): List<String> =
    session.pending().filter { it.type == SyncType.RENAME }.map { it.localPath }

// -- Changes during an open --
