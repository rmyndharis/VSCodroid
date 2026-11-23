package com.vscodroid.storage

import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import com.vscodroid.util.Logger
import com.vscodroid.util.StorageManager
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SeekableByteChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import com.vscodroid.storage.SafSyncEngine.ChangesDuringOpen
import com.vscodroid.storage.SafSyncEngine.Companion.COPY_BUFFER_SIZE
import com.vscodroid.storage.SafSyncEngine.Companion.HELD_BACK_RETRY_FIRST_MS
import com.vscodroid.storage.SafSyncEngine.Companion.HELD_BACK_RETRY_MAX_MS
import com.vscodroid.storage.SafSyncEngine.Companion.PARTIAL_SUFFIX
import com.vscodroid.storage.SafSyncEngine.Companion.localCopyName
import com.vscodroid.storage.SafSyncEngine.Companion.deviceCopyName
import com.vscodroid.storage.SafSyncEngine.Companion.documentAccess
import com.vscodroid.storage.SafSyncEngine.Companion.claimProviderMutation
import com.vscodroid.storage.SafSyncEngine.Companion.releaseProviderMutation
import com.vscodroid.storage.SafSyncEngine.Companion.anotherMirrorWritesDocument
import com.vscodroid.storage.SafSyncEngine.Companion.withStableDocumentRead
import com.vscodroid.storage.SafSyncEngine.Companion.mirrorCopiesInFlight
import com.vscodroid.storage.SafSyncEngine.Companion.inboundScratchPaths
import com.vscodroid.storage.SafSyncEngine.Companion.SYNCED_RECORD_SUFFIX
import com.vscodroid.storage.SafSyncEngine.Companion.HELD_SAVE_WAIT_MS
import com.vscodroid.storage.SafSyncEngine.Companion.HELD_SAVE_COPY_BYTES
import com.vscodroid.storage.SafSyncEngine.Companion.SORTED_RECORD_SUFFIX
import com.vscodroid.storage.SafSyncEngine.Companion.RECORD_HEADER
import com.vscodroid.storage.SafSyncEngine.Companion.escapeRecordPath
import com.vscodroid.storage.SafSyncEngine.Companion.recordLine
import com.vscodroid.storage.SafSyncEngine.Companion.splitRecordLine
import com.vscodroid.storage.SafSyncEngine.Companion.unescapeRecordPath
import com.vscodroid.storage.SafSyncEngine.Companion.MAX_WATCHED_DIRECTORIES
import com.vscodroid.storage.SafSyncEngine.Companion.MAX_FILE_SIZE
import com.vscodroid.storage.SafSyncEngine.Companion.UNANSWERED_SIZE
import com.vscodroid.storage.SafSyncEngine.Companion.OPEN_SPACE_FLOOR_BYTES
import com.vscodroid.storage.SafSyncEngine.Companion.bytesToFetch
import com.vscodroid.storage.SafSyncEngine.Companion.shouldOverwriteMirror
import com.vscodroid.storage.SafSyncEngine.Companion.resolvesInside
import com.vscodroid.storage.SafSyncEngine.Companion.sha256
import com.vscodroid.storage.SafSyncEngine.Companion.readFully
import com.vscodroid.storage.SafSyncEngine.Companion.copyAtMost
import com.vscodroid.storage.SafSyncEngine.Companion.isLink

// Inbound SAF-to-mirror sync, staging, conflict preservation, and installation helpers for [SafSyncEngine].

internal fun SafSyncEngine.initialSyncHoldingMirror(
    safUri: Uri,
    mirrorDir: File,
    onProgress: (Int, Int) -> Unit,
    changes: ChangesDuringOpen? = null,
    receiptsTaken: MutableMap<String, String> = HashMap(),
) {
    reclaimInboundScratch()
    // By mirror name rather than by tree URI: the URI spells the user's own
    // directory path, and Logger.i is not gated on a debuggable build. See
    // SafStorageManager.persistPermission for the whole of that reasoning.
    Logger.i(tag, "Starting initial sync of ${mirrorDir.name}")
    val startTime = System.currentTimeMillis()

    // A closing watcher may still drain while provider reads are in flight.
    unfetched.add(mirrorDir.absolutePath)
    // Clear cache for fresh sync
    resetCache(safUri)
    // Scoped rather than cleared: this engine serves one folder at a time, and
    // a switch that fails partway must not drop the protection the previous
    // folder's mirror is still relying on.
    unfetched.removeAll { it.startsWith(mirrorDir.absolutePath + File.separator) }
    fetchedPaths.removeAll { it.startsWith(mirrorDir.absolutePath + File.separator) }
    keptFromCreates.removeAll { it.startsWith(mirrorDir.absolutePath + "\n") }
    // What an earlier open or save delivered and could not record, the record being unreadable then: written
    // into it before this open reads it, or, where that write fails, read in its place by this open and put back
    // to wait unless the record this open writes holds it ([initialSync]).
    val receiptsBefore = writePendingReceipts(mirrorDir).also { receiptsTaken.putAll(it) }
    refusalsAnnounced.removeAll { it.startsWith(mirrorDir.absolutePath + File.separator) }
    deviceSeen.keys.removeAll { it.startsWith(mirrorDir.absolutePath + File.separator) }
    // Taken aside rather than dropped: phase 2's no-clock arm puts back the hold of a
    // file it leaves as it is, the one save this open does not settle.
    val heldBefore =
        heldBack.filterKeys { it.startsWith(mirrorDir.absolutePath + File.separator) }
    heldBack.keys.removeAll { it.startsWith(mirrorDir.absolutePath + File.separator) }
    settledHeldWait.keys.removeAll { it.startsWith(mirrorDir.absolutePath + File.separator) }
    for ((path, held) in heldBefore) {
        settledHeldWait[path] = held.wait
    }
    keptOnDevice.removeAll { it.startsWith(mirrorDir.absolutePath + File.separator) }

    // Phase 1: Enumerate all documents in the tree
    val documents = mutableListOf<DocumentInfo>()
    val rootDocId = DocumentsContract.getTreeDocumentId(safUri)
    cacheDocId(safUri, "", rootDocId)  // root entry
    val skippedDirs = mutableListOf<String>()
    val enumerationComplete = walkTreeImpl(safUri, rootDocId, "", documents, skippedDirs)
    // Device content this sync deliberately did not read, which is what
    // `unfetched` means. An unread directory guards its descendants as well
    // as deletion of itself or an ancestor. Scrubbed with the rest of the mirror's
    // prefix at the top of this function, so a folder that stops holding one
    // stops being held back by it.
    for (relative in skippedDirs) {
        unfetched.add(File(mirrorDir, relative).absolutePath)
    }

    // What phase 2 is about to fetch, against what there is room for. Nothing is
    // refused on the strength of it: these are the sizes the provider reported, and a
    // folder that opens is worth more than a prediction. It decides one thing, which
    // notice the user gets if a copy does fail, and that is the difference between a
    // message they can act on and one they cannot. What does refuse a fetch is the
    // floor in [copyDocumentToLocal], which reads the disk rather than the provider.
    val toFetch = bytesToFetch(documents, mirrorDir)
    val room = usableSpaceOf(mirrorDir)
    val outOfRoom = toFetch > room
    if (outOfRoom) {
        Logger.w(
            tag,
            // Decimal, like every other MB this app prints. A maintainer
            // reading this beside the storage screen or the low-storage
            // message is comparing the same unit; see
            // StorageManager.formatSize, which decides it once.
            "Opening ${mirrorDir.name} needs ${toFetch / 1_000_000} MB and " +
                "${room / 1_000_000} MB is free; some documents will not be copied",
        )
    }

    val totalFiles = documents.count { !it.isDirectory }
    var filesDone = 0
    var skippedLarge = 0
    var failedCopies = 0
    var keptLocal = 0
    var setAside = 0
    /*
     * What the mirror holds, for the files this sync can vouch for. Filled only where
     * the mirror copy is known to correspond to the device document (it was just
     * written, or it was found identical), and where a fetch that did not happen left
     * the copy an earlier sync vouched for exactly as it was, whose line is carried, or,
     * where no record could be read, left a copy nothing vouches for, whose own line goes
     * out without a digest.
     *
     * An allowlist, and that is the whole point. The previous version recorded every
     * enumerated path and then subtracted the ways that could be wrong, which meant a
     * way nobody had thought of defaulted to "safe to delete". Four were found that
     * way, each a separate patch. Here a branch that does not record leaves its path
     * out, so an unforeseen one defaults to "never a candidate".
     */
    val recorded = mutableListOf<String>()
    // The digest of the copy each [recorded] line names, where this open took one:
    // phase 2's fetches and [digestKeptCopies]. Written onto the line with the record.
    val digests = HashMap<String, ByteArray>()
    var uploadsDone = 0

    Logger.i(tag, "Enumerated ${documents.size} items ($totalFiles files)")

    // Loaded once, before any doc is considered: the uploads a previous
    // session started and nothing finished. Their documents' timestamps are
    // this app's own interrupted writes, and phase 2 below has to know that
    // before it asks the clock anything.
    val unfinishedUploads = uploadsInFlight().toMutableSet()
    // Of those, the lines a write may have left bytes under: not one whose every try failed before the
    // provider was handed a stream ([markUnwritten]). Read only where such a line meets its document.
    val partlyWritten by lazy { partlyWrittenUploads() }

    // Loaded for the same reason and read the same way: what the last sync wrote,
    // as (path, mtime, size) lines, each with the digest of its copy where an open
    // took one. Four readers, each of which needs it only in a
    // case it can rule out cheaply first: [shouldOverwriteMirror] for providers that
    // omit COLUMN_LAST_MODIFIED, where there is no clock to compare;
    // [setAsideDivergedMirror] when a device document is strictly newer than the
    // mirror copy it is about to replace; [deviceChangedSinceRecord] when a mirror
    // copy is strictly newer than the device document it is about to replace; and
    // [uploadMirrorOnlyDocuments] when the mirror holds a file the enumeration did
    // not return. A fifth comes after phase 2: [digestKeptCopies], which takes a kept
    // copy's digest from its line rather than reading the copy again. A sixth is the
    // no-clock arm that finds the two sides apart, through [previousLineFor]. A seventh
    // is phase 3, which carries a line's digest from it wherever this open took none.
    // Read once here rather than per document; [reconcileDeletions] reads the same file
    // again in phase 3, after phase 2 may have changed what is on disk. A read that fails
    // here makes phase 3 write nothing either: what phase 2 carried and vouched for without
    // the record's digests would replace the ones the record holds.
    //
    // `by lazy`, and it takes both halves to make that true. This was passed to
    // [shouldOverwriteMirror] by value, and Kotlin evaluates arguments before the
    // call, so the first ordinary file forced the read whether or not its provider
    // reported a time: the deferral the word promised never happened on any folder
    // with a file in it. Every reader takes a function now and invokes it only from
    // the branch that needs an answer, so a folder that nothing has changed since
    // the last sync does not hold one string per mirrored file through phase 2 for a
    // question no branch is going to ask.
    //
    // Note the scope carefully: it is this binding that is deferred, not the file.
    // [reconcileDeletions] opens the same record again in phase 3 on every sync
    // that enumerates anything, whatever the provider reports, so the saving is the
    // live map across phase 2, never "the record is not read". Nor the parse, once a
    // copy was kept: [digestKeptCopies] asks after it for every copy kept without a
    // digest, which on a folder nothing has changed since the last sync is every file.
    //
    // Null rather than an empty set when there is no record to read, and that
    // distinction is the whole of what the readers below disagree about. A record
    // that is absent, unreadable, or written in a format this build does not know
    // says NOTHING about any path; a record that parsed and lists nothing says the
    // last sync could vouch for no file. Collapsing the two into "empty" left
    // [uploadMirrorOnlyDocuments] reading silence as evidence: with no usable
    // record every mirror file the enumeration did not return became an upload
    // candidate, so a document the user deleted on the device was put straight back
    // onto it -- the one thing that pass documents as never allowed. The header
    // arrived after a build that wrote records without one, so a record this build
    // cannot verify is in the field and reachable on upgrade.
    var recordUnreadable = false
    val previousRecord: Map<String, ByteArray?>? by lazy {
        readSyncedRecord(File(mirrorDir.path + SYNCED_RECORD_SUFFIX)).also { if (it == null) recordUnreadable = true }?.let { lines ->
            if (lines.firstOrNull() == RECORD_HEADER) {
                withReceipts(lines.drop(1), receiptsBefore.values).associate { splitRecordLine(it) }
            } else {
                null
            }
        }
    }
    val previouslyRecorded: Set<String>? by lazy { previousRecord?.keys }
    // When the record was last written, read before this open writes it again: a file older than that
    // was not made in a folder the listing calls empty since then. A record of an earlier build keeps the
    // time its build last wrote it until an open sorts it ([writeSyncedRecord]).
    val recordWrittenAt = File(mirrorDir.path + SYNCED_RECORD_SUFFIX).lastModified()
    // A record with no mark that this build sorted it, so one an earlier build wrote. Builds up to 1.9.0
    // could drop the row of a copy, which then read as work only this app had; this build drops no row of a
    // copy it did not fetch. Only such a record leaves copies that need sorting, and the open that sorts them
    // writes the record anew with the mark, once. An open that wrote the record in place of one it could not
    // read, over a listing that missed some folders, leaves it unmarked too: the copies under those folders
    // have no row yet ([reconcileDeletions]).
    val earlierBuildRecord = recordWrittenAt > 0L && !File(mirrorDir.path + SORTED_RECORD_SUFFIX).exists()
    // Rows without a digest for the files this open keeps inside the app because it cannot place them: a
    // copy a kind conflict sets aside where the record names it without a digest, and over an earlier
    // build's record the older files only the mirror holds. Phase 2b leaves them out and phase 3 writes them.
    val unplaced = ArrayList<String>()
    // The documents the device lists as files that phase 2 found nothing at in the mirror: where a removal took
    // a copy from its name and was cut off, the copy is beside it ([discardCutOffRemovalCopies]).
    val absentAtOpen = HashSet<String>()
    // The previous record's line for a path, whatever copy it names, by the path as the
    // record spells it. Built only once a no-clock open finds the two sides apart.
    val previousLineFor: Map<String, String>? by lazy {
        previousRecord?.keys?.associateBy { it.substringBefore('\t') }
    }

    // Where the mirror ends, resolved rather than compared lexically, and the one
    // thing phase 2 refuses to write outside. Every outbound path in this engine
    // already declines a symbolic link and says why; the inbound copy did not ask at
    // all, and it is the direction that needs the wider question. [isLink]
    // deliberately answers about an entry rather than about its parents, which is
    // right where it is used and wrong here: `mkdirs` follows a link above the file,
    // and so does the rename that moves a finished copy into place, so a single
    // `docs -> ../../..` planted by a checkout or a terminal put every device
    // document under `docs/` wherever that pointed: this app's own private storage,
    // outside the folder the user granted and outside anything [reconcileDeletions]
    // is allowed to touch.
    //
    // A mirror whose own path will not resolve leaves nothing here able to say
    // where a destination lands, so phase 2 does not run at all. That is the same
    // direction [reconcileDeletions] takes for the same question, and it is
    // unreachable in practice: the directory was created by the caller moments ago.
    val confine = try {
        mirrorDir.canonicalPath + File.separator
    } catch (e: Exception) {
        Logger.e(tag, "Could not resolve the mirror's own path; copying nothing: ${e.message}")
        return
    }

    // Phase 2: Create directories and copy files
    for (doc in documents) {
        val localPath = File(mirrorDir, doc.relativePath)

        // Deleted or moved away while this open ran, before it reached the path: fetched
        // back, it undid the person's deletion, and [startWatching] now sends that
        // deletion instead. Asked first, because what this loop sets aside itself raises
        // the same events for the path it is on. The document is left as the device
        // holds it, unread, with no stamp, as a copy an open could not place: only its
        // bytes may let the delete through, compared by [keepsDeviceDocument] with the
        // previous record's digest, and with none the device keeps it and says so.
        if (changes != null && !localPath.exists() && !isLink(localPath) && changes.removed(localPath)) {
            if (!doc.isDirectory) {
                val line = previousLineFor?.get(escapeRecordPath(doc.relativePath))
                val length = line?.split('\t')?.getOrNull(2)?.toLongOrNull() ?: 0
                deviceSeen[localPath.absolutePath] = DeviceState(null, line?.let { previousRecord?.get(it) }, length)
                filesDone++
                onProgress(filesDone, totalFiles)
            }
            continue
        }

        // The DIRECTORY the copy will work in, not the entry itself, and the
        // difference is what keeps this from refusing a case that is already safe:
        // a link at the leaf is replaced rather than written through, because
        // [copyDocumentToLocal] stages its bytes and renames, and a
        // rename replaces a link. What carries bytes out of the mirror is a link
        // above the file, which is exactly what this resolves.
        if (!resolvesInside(localPath.parentFile, confine)) {
            Logger.w(
                tag,
                "Not copying ${doc.relativePath} in: the directory it would be " +
                    "written in resolves outside the mirror",
            )
            if (!doc.isDirectory) {
                failedCopies++
                // The device holds a document this sync did not read, which is
                // exactly what the set means and what keeps a local file of that
                // name from opening it with "wt" and truncating it.
                unfetched.add(localPath.absolutePath)
                filesDone++
                onProgress(filesDone, totalFiles)
            }
            continue
        }

        if (doc.isDirectory) {
            clearKindConflict(doc, localPath, mirrorDir, { previousRecord }) { unplaced += it }
            localPath.mkdirs()
        } else {
            clearKindConflict(doc, localPath, mirrorDir, { previousRecord }) { unplaced += it }
            // First, so it covers every arm below: each one either arms `unfetched`,
            // which blocks writes on its own, leaves the device as enumerated, or
            // writes it, and the write refreshes this entry.
            deviceSeen[localPath.absolutePath] = DeviceState(doc.lastModified to doc.size)
            // The one thing no timestamp comparison can see: this app was
            // writing this path back when it stopped. A newer, shorter
            // document here is CONSISTENT with this app's own truncated copy,
            // which is not the same as being one, so the bytes are asked
            // rather than assumed below. Where they answer yes the mirror is
            // kept, it is the only complete version, and the upload is
            // attempted again, which is also the repair. The record is
            // consumed by the sync that acted on it; the re-upload re-marks
            // it if it is cut short again.
            //
            // Repaired here rather than queued: the queue belongs to the
            // watcher's lifecycle, and [startWatching] opens by calling
            // [stopWatching], whose no-worker branch clears the queue, so a
            // job offered between the sync and the thread that would run it
            // is discarded before anything executes it. The sync already
            // owns this moment; it is on the IO dispatcher and mid-copy.
            if (localPath.exists() && localPath.absolutePath in unfinishedUploads) {
                val mutation = claimProviderMutation(doc.uri)
                if (mutation == null) {
                    keptLocal++
                    deferWriteBack(localPath, doc.uri)
                    filesDone++
                    onProgress(filesDone, totalFiles)
                    continue
                }
                try {
                    // The journal line is not the licence the comment above reads
                    // it as. It says a write started here and did not finish; it
                    // says nothing about what touched the document between then
                    // and now, and the window is as long as the app was away. A
                    // user who edits that file on the device before opening the
                    // app again, or another app that rewrites it, leaves a
                    // document this branch used to overwrite with `"wt"`, which
                    // truncates at open. The bytes were gone and nothing had been
                    // kept.
                    //
                    // [deviceIsTruncatedMirror] is what the comment above assumed
                    // rather than checked: the interrupted write leaves a strict
                    // prefix of the mirror, so anything else came from somewhere
                    // else. In the expected case it costs one provider read and
                    // no copy, which is why it is asked before the set-aside
                    // rather than instead of it: routing every repair through
                    // [setAsideDeviceCopy] would keep a `.device-` copy of this
                    // app's own half-written bytes, and phase 2b would then put
                    // that copy on the device.
                    //
                    // Not for a line whose every try failed before the provider was handed a stream:
                    // nothing of this app's is on the device then, and a prefix there is what another
                    // app left, by cutting the end of the text.
                    val ownPartialWrite = localPath.absolutePath in partlyWritten &&
                        !anotherMirrorWritesDocument(doc.uri, mirrorDir) &&
                        deviceIsTruncatedMirror(doc.uri, localPath, mirrorDir, doc.size)
                    val deviceCopy =
                        if (ownPartialWrite) null else setAsideDeviceCopy(doc, localPath, mirrorDir)
                    if (deviceCopy == DeviceCopyOutcome.IN_FLIGHT) {
                        keptLocal++
                        val wait = heldBefore[localPath.absolutePath]
                            ?.let { minOf(it.wait * 2, HELD_BACK_RETRY_MAX_MS) }
                            ?: HELD_BACK_RETRY_FIRST_MS
                        heldBack[localPath.absolutePath] =
                            HeldBackSave(doc.uri, SystemClock.elapsedRealtime() + wait, wait)
                        Logger.w(
                            tag,
                            "Deferring ${doc.relativePath}: its device copy was involved " +
                                "in another write, so the mirror keeps its copy and " +
                                "retries when that write finishes",
                        )
                        announceLost(localPath)
                        filesDone++
                        onProgress(filesDone, totalFiles)
                        continue
                    }
                    if (deviceCopy == DeviceCopyOutcome.UNAVAILABLE) {
                        // Held back, not abandoned, and the record is deliberately
                        // NOT consumed: the mirror keeps the only complete copy,
                        // unvouched, and the next open tries the whole repair
                        // again. Writing anyway would be the loss this guard
                        // exists to prevent.
                        keptLocal++
                        // The device holds a document this sync did not read, which
                        // is what the set means and what stops the next save opening
                        // it with "wt" and truncating it. Recorded here for the same
                        // reason the confine refusal, the size skip and a failed copy
                        // all record it: this branch ends the same way, having
                        // declined to read the device copy. Without it the refusal
                        // protects the document for exactly as long as the sync runs,
                        // and the first edit afterwards overwrites the bytes it would
                        // not overwrite here. Self-clearing: `initialSync` scrubs the
                        // mirror's prefix on every reopen, so a later sync that CAN
                        // set the device copy aside leaves the path out again.
                        unfetched.add(localPath.absolutePath)
                        Logger.w(
                            tag,
                            "Not replacing ${doc.relativePath}: its device copy is not the " +
                                "unfinished upload this app left and could not be set " +
                                "aside, so the mirror keeps its copy and the next open " +
                                "tries again",
                        )
                        // Said on screen as well, for the reason every sibling
                        // path here says it: the mirror now holds a copy no other
                        // app can see, that an uninstall takes with it, and a log
                        // line is not an answer to a user who thinks they saved.
                        // Throttled twice over, per path here and per interval in
                        // SafStorageManager, so a folder full of them is one
                        // notice rather than a wall of them.
                        announceLost(localPath)
                        filesDone++
                        onProgress(filesDone, totalFiles)
                        continue
                    }
                    unfinishedUploads.remove(localPath.absolutePath)
                    consumeStaleUploadRecordImpl(localPath.absolutePath)
                    keptLocal++
                    if (deviceCopy == DeviceCopyOutcome.PRESERVED) setAside++
                    Logger.i(
                        tag,
                        when (deviceCopy) {
                            null ->
                                "Kept the mirror of ${doc.relativePath}: its device copy " +
                                    "was an upload this app did not finish"
                            // The line a maintainer reads to explain a `.device-` file in
                            // a user's folder, so it is said only where one was really
                            // left behind. It used to be said for the identical-bytes case
                            // too, which is the case that deletes its own copy again.
                            DeviceCopyOutcome.PRESERVED ->
                                "Kept the mirror of ${doc.relativePath}: its device copy " +
                                    "did not come from an unfinished upload and was " +
                                    "preserved first"
                            else ->
                                "Kept the mirror of ${doc.relativePath}: its device copy " +
                                    "did not come from an unfinished upload and holds the " +
                                    "same bytes, so nothing was set aside"
                        },
                    )
                    writeLocalToSafImpl(localPath, doc.uri, mirrorDir, mutation)
                    filesDone++
                    onProgress(filesDone, totalFiles)
                    continue
                } finally {
                    releaseProviderMutation(mutation)
                }
            }
            // Skip files larger than MAX_FILE_SIZE.
            // Not recorded, because this sync did not fetch the file and so cannot
            // say what is at that path. Usually there is nothing; but a file that was
            // under the limit at an earlier sync and has since grown past it leaves a
            // real, sync-written copy sitting there, and "nothing ever wrote this"
            // would be the wrong reason for the right behaviour. What the previous
            // record said of that copy is carried while the copy is still exactly the
            // one it names, as for a failed copy below: dropped, the copy read as a
            // save the device never got, and a deletion of the document on the device
            // afterwards put it back there with its old bytes. Where no record could be read
            // there is no line to carry, and the copy's own line is written without a digest
            // ([unprovenRows] says what such a row does), for the same reason.
            if (doc.size > MAX_FILE_SIZE) {
                skippedLarge++
                unfetched.add(localPath.absolutePath)
                val line = identityLine(doc.relativePath, localPath)
                if (localPath.isFile && previouslyRecorded?.contains(line) != false) recorded += line
                else if (earlierBuildRecord && isOlderCopyOfGrownDocument(doc, localPath) { previousLineFor }) recorded += line
                // At Logger.i, not d, for the reason the no-clock line below gives:
                // Logger.d is gated on a debuggable build, so on a release build
                // the only trace of this document was the count in the summary. The
                // file is intact on the device and the editor simply does not have
                // it, which is a folder that opens successfully and is quietly
                // incomplete, and a user asking why they cannot find it needs the
                // name rather than a number.
                Logger.i(tag, "Skipped large file: ${doc.relativePath} (${doc.size / 1_000_000} MB)")
                filesDone++
                onProgress(filesDone, totalFiles)
                continue
            }
            val localExists = localPath.exists()
            if (!localExists) absentAtOpen += doc.relativePath
            if (!shouldOverwriteMirror(
                    localExists, localPath.lastModified(), localPath.length(),
                    doc.lastModified, doc.size,
                    mirrorIsOurCopy = {
                        // A record this build cannot verify vouches for nothing,
                        // which is what the unknown-time branch already does with
                        // an answer of false: it keeps the mirror copy.
                        identityLine(doc.relativePath, localPath) in
                            previouslyRecorded.orEmpty()
                    },
                )
            ) {
                keptLocal++
                var deviceAgrees = false
                // Inside this branch the mirror is not older, so equal means the
                // mirror is already this document and can be vouched for, while
                // strictly newer means it holds an edit that has not been written
                // back and cannot.
                if (localPath.lastModified() == doc.lastModified) {
                    recordIdentity(recorded, doc.relativePath, localPath, mirrorDir)
                } else if (doc.lastModified == 0L &&
                    deviceMatchesUnchangedMirror(doc.uri, localPath, doc.size, mirrorDir) { _, attributes ->
                        recordIdentity(recorded, doc.relativePath, attributes)
                    }
                ) {
                    // No clock, the mirror no longer matches the record, and yet the
                    // device document holds exactly these bytes. That is the ordinary
                    // end of a local edit: the watcher carried it to the device
                    // already, so the two agree in content while the record still
                    // describes what was there before.
                    //
                    // Only for a file nothing wrote while it was read. One that was
                    // written is not what the read compared, and recorded it would be
                    // the copy the record vouches for: the next open replaced it with
                    // the device's older bytes, with nothing set aside, and the edit
                    // was gone. It takes the branch for a copy that differs.
                    //
                    // Nothing here writes anything. It records what is already true
                    // on both sides, which is what the record means and what
                    // `holdsOnlyVouchedCopies` reads it to mean.
                    //
                    // This recovers two of the three costs of never being vouched:
                    // device changes reach the file again, and the mirror becomes
                    // reclaimable. It does not recover the third. An edit the watcher
                    // never delivered is not on the device, so this cannot fire for
                    // it, and with no clock there is nothing to say whether the
                    // device copy is older or newer, so it is not pushed either.
                    //
                    // Deliberately not paired with a write in the other direction. If
                    // the two do not agree there is no clock to say which is newer,
                    // and pushing either way would destroy the other side's work.
                    deviceAgrees = true
                    Logger.i(
                        tag,
                        "Tracking ${doc.relativePath} again: the provider reports no " +
                            "modification time, and the device copy already holds " +
                            "what the mirror holds",
                    )
                } else if (doc.lastModified > 0L &&
                    localPath.lastModified() > doc.lastModified
                ) {
                    val mutation = claimProviderMutation(doc.uri)
                    if (mutation == null) {
                        deferWriteBack(localPath, doc.uri)
                    } else try {
                        // Strictly newer with a time to compare against: an edit the
                        // watcher never carried out. That happens whenever the watcher
                        // was not running -- the app was killed, the directory sat past
                        // the watch cap, or the write-back gave up -- and until now
                        // nothing ever retried it. The prose around this feature calls
                        // reopening the folder the way back; this is what makes that
                        // true.
                        //
                        // Only when the provider reported a time. An unknown one arrives
                        // as 0, and every real mirror timestamp is greater than 0, so
                        // without this guard every file in such a folder would look
                        // newer and be pushed over the device document on every reopen.
                        //
                        // Deliberately NOT capped at [MAX_FILE_SIZE], which the same loop
                        // applies a few lines up, and the asymmetry is the right way
                        // round. That cap keeps a large DEVICE document out of
                        // `filesDir`, which is this app's own storage and the thing that
                        // runs out; writing back spends the device folder's space
                        // instead, so the reason for the cap does not apply in this
                        // direction.
                        //
                        // The decisive argument is consistency, not data loss. Capping
                        // costs nothing permanent -- an uncapped file simply stays in
                        // the mirror, where nothing removes it and [holdsOnlyVouchedCopies]
                        // says the record cannot vouch for it -- but none of the other three
                        // `writeLocalToSaf` sites cap, so a cap here alone would upload
                        // a file when the watcher saves it and refuse the same file on
                        // reopen. One engine, two answers to one question, is worse
                        // than either answer.
                        //
                        // Note also how narrow the case is: reaching here needs the
                        // DEVICE document under the cap, since a larger one is skipped
                        // above, so only a file the user grew past 50 MB locally
                        // qualifies.
                        //
                        // What it costs is honest: a large file makes the folder-open
                        // dialog sit on this one entry. Progress is per file, so the bar
                        // does not move until the copy finishes. This runs on
                        // Dispatchers.IO, so it is a slow dialog and not an ANR.
                        //
                        // "Newer" says which side moved last, not that only one side
                        // moved. The record carries the device time this file had at
                        // the last sync, so a device document that no longer matches it
                        // was written since by something other than this app's sync,
                        // and the write below opens it with "wt". What wrote it is not
                        // knowable from the time alone: another app is the case that
                        // matters, and this app's own delivered write-back is the common
                        // one, since the record never learns the time that write left
                        // behind. Its line carries the digest of the delivered bytes,
                        // which [deviceChangedSinceRecord] compares wherever the sizes
                        // agree, so such a file costs that read and no copy. The
                        // mirror-side guard for the symmetric case already exists
                        // ([setAsideDivergedMirror]); this is the same guard facing
                        // the other way, and the copy it fetches is paid wherever the
                        // record cannot vouch for the path: where its bytes show the
                        // device moved, and where it says nothing at all, which a line
                        // with no digest, as builds up to 1.9.0 wrote, also is. There a
                        // second mirror edit before the next open fetches this app's own
                        // older bytes, which differ, and keeps them beside the file.
                        // That is the known false positive [setAsideDivergedMirror]
                        // already names for the two other producers of silence.
                        //
                        // A copy that fails leaves both sides as they are: the mirror
                        // keeps its edit, unvouched, and the next open tries again. The
                        // fetch failing is therefore a deferral, not a loss, but it is a
                        // deferral of the user's own save and nothing but this log says
                        // so, which for a persistent cause is indistinguishable from the
                        // save never happening.
                        val deviceCopy = when {
                            anotherMirrorWritesDocument(doc.uri, mirrorDir) -> DeviceCopyOutcome.IN_FLIGHT
                            previousRecord?.let { deviceChangedSinceRecord(it, doc) } != false ->
                                setAsideDeviceCopy(doc, localPath, mirrorDir)
                            else -> null
                        }
                        if (deviceCopy == DeviceCopyOutcome.IN_FLIGHT) {
                            val wait = heldBefore[localPath.absolutePath]
                                ?.let { minOf(it.wait * 2, HELD_BACK_RETRY_MAX_MS) }
                                ?: HELD_BACK_RETRY_FIRST_MS
                            heldBack[localPath.absolutePath] =
                                HeldBackSave(doc.uri, SystemClock.elapsedRealtime() + wait, wait)
                            Logger.w(
                                tag,
                                "Deferring ${doc.relativePath}: its device copy was " +
                                    "involved in another write, so the mirror keeps " +
                                    "the edit and retries when that write finishes",
                            )
                            announceLost(localPath)
                        } else if (deviceCopy == DeviceCopyOutcome.UNAVAILABLE &&
                            usableSpaceOf(mirrorDir) < OPEN_SPACE_FLOOR_BYTES &&
                            deviceMatchesUnchangedMirror(doc.uri, localPath, doc.size, mirrorDir) { destination, _ ->
                                runCatching { destination.modified(doc.lastModified) }
                                recordIdentity(recorded, doc.relativePath, destination.attributes())
                            }
                        ) {
                            // The floor held back the fetch the set-aside needed, and a
                            // read says there was nothing to set aside or write back: the
                            // device holds the mirror's bytes. The ordinary case is a save
                            // the watcher delivered to a provider whose clock is coarser
                            // than the mirror's, which rounds the write-back's time to
                            // before the save. Settled like phase 2's own held-back fetch
                            // below, stamped with the device's time and recorded, so it is
                            // neither announced nor refused its saves for the session.
                            //
                            // Only for a file nothing wrote while it was read. One that
                            // was written is not what the read compared, and stamping it
                            // with the device's older time would record an edit the
                            // device never received as delivered; it takes the deferral
                            // below, as a read that found other bytes does.
                        } else if (deviceCopy == DeviceCopyOutcome.UNAVAILABLE) {
                            // Unread, exactly as in the repair branch above, and armed
                            // for the same reason: this declines to touch the device
                            // copy now, and nothing else would stop the next save
                            // doing what this refused.
                            unfetched.add(localPath.absolutePath)
                            Logger.w(
                                tag,
                                "Not writing ${doc.relativePath} back: the record cannot " +
                                    "vouch for its device copy and that copy could not be " +
                                    "set aside, so the mirror keeps the edit and " +
                                    "the next open tries again",
                            )
                            // The deferral this branch's comment describes, said
                            // to the user rather than to logcat alone. Same seam,
                            // same throttles and same sentence as every other
                            // path that ends with the only copy inside the app.
                            announceLost(localPath)
                        } else {
                            if (deviceCopy == DeviceCopyOutcome.PRESERVED) setAside++
                            uploadsDone++
                            Logger.i(tag, "Writing back a newer mirror copy: ${doc.relativePath}")
                            writeLocalToSafImpl(localPath, doc.uri, mirrorDir, mutation)
                        }
                    } finally {
                        releaseProviderMutation(mutation)
                    }
                }
                // The flag the branch above set, not a fresh stat plus a scan of the
                // recorded list. Re-deriving it would also read "the two differ" out of
                // a recordIdentity that simply declined the path.
                if (doc.lastModified == 0L && !deviceAgrees) {
                    // Not the listed stamp as what this engine saw, which let the next
                    // save or delete go over the device copy unasked: it holds bytes the
                    // open could not place, another app's edit as readily as the copy
                    // the record vouched for before an edit the watcher never delivered,
                    // or a save the session delivered before it lost a later one. So no
                    // stamp, and the digest of the copy the record vouched for: the next
                    // save or delete reads the device copy, goes ahead over that
                    // version, and keeps anything else the way [keepsDeviceEdit] keeps
                    // another app's edit, this app's own delivered save included; see
                    // there for why the record cannot tell that one apart.
                    val last = previousLineFor?.get(escapeRecordPath(doc.relativePath))
                    deviceSeen[localPath.absolutePath] = DeviceState(
                        null,
                        last?.let { previousRecord?.get(it) },
                        last?.substringAfterLast('\t')?.toLongOrNull() ?: 0,
                    )
                    // A save held back before this open is in the mirror and not on the
                    // device, and nothing here sent it: dropped with the other holds, it
                    // waited for the next save of the file, with no notice after the
                    // open. Tried again as before, and the entry above makes the try
                    // read the device copy, so another app's edit is kept beside it.
                    // Through the document listed here rather than the one it was held
                    // for, which another app may have replaced since.
                    heldBefore[localPath.absolutePath]?.let {
                        heldBack[localPath.absolutePath] =
                            HeldBackSave(doc.uri, it.dueAt, it.wait)
                    }
                    // At Logger.i, not d: this file stops receiving device-side
                    // changes from here on, and a user asking why their folder does
                    // not update needs this line to exist in a bug report.
                    Logger.i(
                        tag,
                        "Kept ${doc.relativePath}: the provider reports no " +
                            "modification time, and the device copy differs from the " +
                            "mirror, so nothing here can say which is newer. Both " +
                            "sides are left as they are.",
                    )
                }
                Logger.d(tag, "Kept local copy: ${doc.relativePath}")
                filesDone++
                onProgress(filesDone, totalFiles)
                continue
            }
            localPath.parentFile?.mkdirs()
            // One sync at a time per mirror file, and the loser walks away rather
            // than waiting, which is the shape [documentAccess] already
            // uses on the outbound side and for the same measured reason: a
            // provider stream has no timeout, so waiting turns a race into a stall
            // behind a dialog the user cannot cancel. Two syncs over one mirror is
            // not exotic: the mirror is named by a hash of the tree URI rather than
            // by the session, the phase-2 loop has no suspension point so cancelling
            // the coroutine does not stop it, and an activity recreated mid-open
            // builds a second engine over the same directory. Both used to write the
            // same scratch path, so one writer's rename pulled the inode out from under
            // the other, whose remaining bytes then landed inside the finished
            // mirror file that [recordIdentity] had already vouched for.
            if (!mirrorCopiesInFlight.add(localPath.absolutePath)) {
                // Armed for the same reason every other refusal in this loop
                // arms it: THIS sync did not read the device document, which
                // is the whole of what the set means. Leaving it to the other
                // sync is right for the copy and says nothing about the guard,
                // and the two engines do not share one: the claim above is in
                // the companion object precisely because two engines meet
                // over one mirror, while [unfetched] is per engine.
                //
                // Which engine is left holding the folder makes it worse, not
                // academic. The routine way two syncs meet here is an activity
                // recreated mid-open: the claim belongs to the engine of the
                // activity being destroyed, so the winner is the one going
                // away and the LOSER is the engine whose watcher goes on
                // serving saves. A copy the winner never finished then leaves
                // the mirror holding a stub, and the first save writes it over
                // the device document with "wt", which truncates at open.
                //
                // It can only add refusals, never a write, and a refusal here
                // is a deferral the next open clears: [initialSync] scrubs the
                // mirror's prefix out of the set before phase 2. The cost when
                // the winner did finish is one save deferred to the next open
                // of that folder; the cost of not arming it is the document.
                unfetched.add(localPath.absolutePath)
                // The previous record's line is carried while the copy is still the
                // one it names, or written for it with no record to read, for the
                // reason the size limit above does.
                val line = identityLine(doc.relativePath, localPath)
                if (localPath.isFile && previouslyRecorded?.contains(line) != false) recorded += line
                Logger.w(
                    tag,
                    "Another sync of this folder is already copying " +
                        "${doc.relativePath}; leaving it to that one, and not " +
                        "vouching for a device copy this sync never read",
                )
                filesDone++
                onProgress(filesDone, totalFiles)
                continue
            }
            val fetchedDigest = MessageDigest.getInstance("SHA-256")
            var unchangedAfterFailure = true
            val copied = try {
                copyDocumentToLocal(
                    doc.uri, localPath, doc.lastModified, mirrorDir, fetchedDigest,
                    expectedSize = doc.size,
                    onCopyFailed = { unchangedAfterFailure = it },
                ) { fetched, changedDuringFetch, destination ->
                    val moved = setAsideDivergedMirror(
                        doc, localPath, fetched, changedDuringFetch, destination,
                    ) { previousRecord }
                    if (moved) setAside++
                    moved
                }
            } finally {
                mirrorCopiesInFlight.remove(localPath.absolutePath)
            }
            if (copied is LandedCopy) {
                // What the open fetched vouches for the device copy as a landed write's
                // bytes do in [keepsDeviceEdit]. Without it a provider that settles its
                // stamp after the open, a FAT card once the cached inode is evicted or
                // Nextcloud after its upload, had the first save of the session keep a
                // `.device-` copy of the very document the editor shows.
                //
                // Both from the copy as it landed rather than from the mirror file a
                // moment later, which is whatever an editor saved into it since: that
                // length beside this digest made the read at the next save stop short
                // of the fetched copy and keep a duplicate of it, and that identity in
                // the record vouched for an edit the device never received, which the
                // reclaim and phase 3 read as disposable.
                val fetched = fetchedDigest.digest()
                deviceSeen[localPath.absolutePath] =
                    DeviceState(doc.lastModified to doc.size, fetched, copied.length)
                val line = identityLine(doc.relativePath, copied.lastModified, copied.length)
                recorded += line
                digests[line] = fetched
            } else if (localPath.isFile &&
                usableSpaceOf(mirrorDir) < OPEN_SPACE_FLOOR_BYTES &&
                deviceMatchesUnchangedMirror(doc.uri, localPath, doc.size, mirrorDir) { destination, _ ->
                    if (doc.lastModified > 0) runCatching { destination.modified(doc.lastModified) }
                    recordIdentity(recorded, doc.relativePath, destination.attributes())
                }
            ) {
                // Held back by the floor, and nothing was missed by it: the device
                // document holds the mirror's bytes, which a read settles without
                // spending disk. Two ordinary cases land here: on a provider with no
                // clock every file the record vouches for, which the unknown-time
                // branch fetches again on every open, and a file the editor saved and
                // the watcher delivered where the device stamped that write-back after
                // the save, newer on the device by time alone. A clock coarse enough
                // to stamp it before the save takes the strictly-newer arm above, which
                // settles it the same way. Taken as failed copies, each was announced
                // as not copied and refused every save of the session while the editor
                // showed it as the device holds it.
                //
                // Stamped with the device's time, as the copy would have been, so the
                // next open finds it current rather than fetching it again.
                //
                // Only for a file nothing wrote while it was read, as in the arm above;
                // one that was written falls to the failed copy below, which keeps it
                // as the editor wrote it.
                keptLocal++
            } else {
                // Nothing says the mirror holds what the device holds now. Counted as
                // well as guarded: a failed copy had no counter at all, so the summary
                // reported a clean sync for a folder it did not read.
                failedCopies++
                unfetched.add(localPath.absolutePath)
                // What the previous record vouched for still stands while the mirror
                // copy is exactly the copy it vouched for: nothing landed on it since,
                // so the device held these bytes when that line was written, whether or
                // not it has moved on. Dropped, the next open read it as an edit nobody
                // synced, set it aside as a `.local-` file that phase 2b put into the
                // device folder, or with no clock kept it over every later device
                // change, and the reclaim and the storage screen called it work the
                // device folder lacks. Carried rather than made, so not through
                // [recordIdentity], whose precondition is that the device holds these
                // bytes now; the line goes on saying only what it said when it was
                // written. With no record to read it is written without a digest, as
                // at the size limit above.
                val line = identityLine(doc.relativePath, localPath)
                if (unchangedAfterFailure && localPath.isFile && previouslyRecorded?.contains(line) != false) {
                    recorded += line
                }
            }
            filesDone++
            onProgress(filesDone, totalFiles)
        }
    }
    settledHeldWait.keys.removeAll { it.startsWith(mirrorDir.absolutePath + File.separator) }

    digestKeptCopies(mirrorDir, documents, recorded, digests) { previousRecord?.get(it) }
    recorded.forEach {
        fetchedPaths.add(File(mirrorDir, unescapeRecordPath(it.substringBefore('\t'))).absolutePath)
    }

    // Read here, where phase 2 stopped fetching, rather than beside the notice: phase
    // 2b writes into the device folder, which for internal storage is this same
    // partition, and phase 3 frees mirror files, so either could change the answer.
    val belowFloor = failedCopies > 0 && usableSpaceOf(mirrorDir) < OPEN_SPACE_FLOOR_BYTES

    // Before phase 2b: the copy of a deleted document that an earlier open was cut off from
    // removing is no work of the user's, and under the name builds up to 1.9.0 gave it, phase
    // 2b would create it on the device. Guarded by the same two facts as phase 2b and phase 3.
    val discardedCaptures = if (enumerationComplete && documents.isNotEmpty()) {
        previousRecord?.let {
            discardCutOffRemovalCopies(mirrorDir, it, documents, recorded.toHashSet(), absentAtOpen, earlierBuildRecord)
        } ?: 0
    } else {
        0
    }

    // Phase 2b: put onto the device what only the mirror has.
    //
    // Guarded by the same two facts [reconcileDeletions] needs, and for a mirrored
    // reason. A partial enumeration does not know what the device holds, so a
    // mirror file it cannot find would be created beside a document that is already
    // there, or written into it; and an enumeration that succeeded and returned
    // nothing is a folder the user emptied and a provider answering for a volume
    // that is no longer mounted, told apart by nothing. This phase writes to the
    // device, so both cases decline. The exception is a record that holds only its
    // header: nothing was ever vouched for in that mirror, so no file in it can be a
    // document the device deleted, and the folder is the one that was empty when it was
    // first opened and is empty still. A file made in it whose create died with the
    // process is sent; one that cannot be (a volume that is gone) is reported as any
    // other upload that did not finish.
    // The tick is added to the phase-2 figures rather than replacing them, so the
    // dialog goes on counting up instead of restarting at one, and the total grows
    // by exactly the work that is left.
    //
    // Over a record an earlier build wrote, a file only the mirror holds that is older than the record, with
    // no row and no line in the upload journal, may be the copy of a document the device deleted whose row
    // that build dropped: it is kept inside the app with a row of its own, and phase 3 says so. A listing
    // that is partial or empty cannot sort them, and leaves the record's time as it is for an open that can.
    if (earlierBuildRecord && enumerationComplete && documents.isNotEmpty()) {
        previousRecord?.let { previous ->
            val named = previous.keys.mapTo(HashSet()) { unescapeRecordPath(it.substringBefore('\t')) }
            unplaced += unprovenRows(
                mirrorDir, documents.filterNot { it.isDirectory }.mapTo(HashSet()) { it.relativePath }, sortRenamed = true,
            ) { file, path -> path !in named && file.lastModified() <= recordWrittenAt }
        }
    }
    // What did not reach the device, said once for the whole open in two notices: phase 2b's shortfall, which
    // the device refused or the cap left, and the files kept inside the app because the open cannot tell them
    // from a document deleted on the device, by name ([onFilesKeptInApp]), which phase 2b leaves out over an
    // empty listing and phase 3 gives rows.
    var notReached = 0
    var capped = false
    val keptFiles = ArrayList<String>()
    val putBack = if (enumerationComplete && (documents.isNotEmpty() || previousRecord?.isEmpty() == true)) {
        uploadMirrorOnlyDocuments(
            safUri,
            mirrorDir,
            documents.mapTo(HashSet()) { it.relativePath },
            { previousRecord?.map { (identity, digest) -> recordLine(identity, digest) }?.toSet() },
            if (documents.isEmpty()) recordWrittenAt else null,
            unplaced.mapTo(HashSet()) { unescapeRecordPath(it.substringBefore('\t')) },
            { lost, truncated, leftOut ->
                notReached += lost
                capped = capped || truncated
                keptFiles += leftOut
            },
        ) { done, total -> onProgress(filesDone + done, totalFiles + total) }
    } else {
        0
    }

    // The saves this open delivered, recorded with the rest. Each receipt leaves [deliveredDuringSync] only once
    // a record holding it is written: an open that writes none, over a record it cannot read, dropped them,
    // and the next open found the device holding this app's own saves with no row and kept each beside its
    // file as another app's edit.
    val folded = ArrayList<Pair<String, String>>()
    for ((path, receipt) in deliveredDuringSync) {
        if (!path.startsWith(mirrorDir.absolutePath + File.separator)) continue
        val (identity, digest) = splitRecordLine(receipt)
        val relative = identity.substringBefore('\t')
        recorded.removeAll { it.substringBefore('\t') == relative }
        recorded += identity
        if (digest != null) digests[identity] = digest
        fetchedPaths.add(path)
        folded += path to receipt
    }

    // Phase 3: drop what the device no longer has. Each line goes out with the digest
    // this open took of its copy, or else the one the previous record held for it:
    // a line names the copy's time and length, so a digest taken of the copy it names
    // still describes it, which is what carries a digest from open to open.
    var recordWritten = false
    val removed = reconcileDeletionsImpl(
        mirrorDir, documents, enumerationComplete,
        recorded.map { recordLine(it, digests[it] ?: previousRecord?.get(it)) },
        unplaced, recordUnreadable, onWritten = { recordWritten = true },
        onKept = { rows -> keptFiles += rows.map { unescapeRecordPath(it.substringBefore('\t')) } },
        receipts = receiptsBefore.values,
        unlisted = skippedDirs,
    )
    if (recordWritten) {
        folded.forEach { (path, receipt) -> deliveredDuringSync.remove(path, receipt) }
        receiptsTaken.clear()
    }
    changes?.keptByOpen(unplaced)
    val clearedPartials = removeRedundantPartials(mirrorDir, documents)

    val elapsed = System.currentTimeMillis() - startTime
    Logger.i(
        tag,
        "Initial sync complete: $filesDone files ($skippedLarge too large, " +
            "$failedCopies could not be copied, $keptLocal kept, " +
            "$uploadsDone written back, $setAside set aside, $putBack put back, " +
            "${recorded.size} vouched for, $removed removed, $clearedPartials partial copies cleared, " +
            "$discardedCaptures cut-off removals cleared) in ${elapsed}ms"
    )

    // Only the copies that were attempted and failed. A document skipped for size is
    // an expected, permanent condition that would toast on every open of the folder
    // holding it, and the write-back guard already keeps a local file of that name
    // from replacing it.
    if (failedCopies > 0) {
        if (belowFloor) {
            Logger.w(
                tag,
                "${mirrorDir.name} stopped fetching under " +
                    "${OPEN_SPACE_FLOOR_BYTES / 1_000_000} MB free; documents not " +
                    "fetched stay on the device",
            )
        }
        onDocumentsNotCopied(failedCopies, outOfRoom || belowFloor)
    }
    if (notReached > 0 || capped) onUploadIncomplete(mirrorDir, notReached, capped)
    keptFiles.distinct().sorted().takeIf { it.isNotEmpty() }?.let { onFilesKeptInApp(mirrorDir, it.size, it.first()) }
    if ("" !in skippedDirs) unfetched.remove(mirrorDir.absolutePath)
}


/**
 * Moves the mirror copy of [doc] out of the way when the device's copy is about to
 * replace it and nothing can say the mirror held this app's own copy, and answers
 * whether it did.
 *
 * What it stands between: a save the write-back never delivered and a device edit
 * made afterwards. [shouldOverwriteMirror] is last-writer-wins on modification time,
 * so the device copy wins that comparison and [copyDocumentToLocal] replaces the
 * mirror, after which [recordIdentity] vouches for the result. The edit is then gone
 * with no copy of it anywhere: the mirror held the only one. Reaching that state
 * needs nothing exotic, only a save the watcher did not carry (the app killed before
 * the drain ran it, or the file sitting past [MAX_WATCHED_DIRECTORIES]) and then any
 * other app touching the same file on the device.
 *
 * Two conditions keep this from firing on the ordinary case. A destination that
 * changed during the provider read bypasses the record condition: neither missing
 * history nor matching old metadata can vouch for bytes written in that interval.
 *
 * - **No record vouches for the mirror copy.** A file whose mirror
 *   copy still matches the identity and digest the last sync wrote is this app's own
 *   copy and the device is entitled to replace it, which is the same evidence
 *   [holdsOnlyVouchedCopies] and [reconcileDeletions] act on. Missing, unreadable or
 *   unsupported history cannot distinguish a stale copy from an undelivered edit,
 *   so distinct bytes are retained in that case too.
 *
 * - **The fetched bytes differ from the mirror copy.** Without this the guard would
 *   fire on every file the user edited in the last session: a save the watcher DID
 *   deliver leaves the record naming the pre-edit copy and the device carrying the
 *   write-back's own fresh timestamp, so the first condition holds for it too, and
 *   the two sides hold the same bytes. [fetched] is the copy [copyDocumentToLocal]
 *   has just finished staging, so this is a local comparison
 *   and not a second read of the document.
 *
 * One ordinary branch of phase 2 also leaves a path out of the record, and is a known
 * false positive here: a document whose destination directory resolved outside the
 * mirror. If the device copy of such a file later changes, the stale sync-written
 * mirror copy is set aside under a `.local-` name and [uploadMirrorOnlyDocuments]
 * then puts that name onto the device folder. Nothing is lost, and both folders gain
 * a file nothing removes. Narrowing the test would need the reason a path went
 * unrecorded to survive to the next sync, which nothing on disk carries today:
 * `unfetched` is this sync's own set, and every path in it took a branch that never
 * reaches here. A copy that failed, that the free-space floor held back, that was
 * skipped for size or that another open was copying is not among them: phase 2
 * carries the previous record's line for a mirror copy still exactly as that record
 * described it.
 *
 * The copy is renamed rather than duplicated, so the space cost is nothing and what
 * the sync then finds at the original name is absent, which is where the fetched copy
 * is about to land. A failed preservation aborts the incoming replacement.
 *
 * Deliberately not routed through [announceLost]. The set-aside copy is a candidate
 * for [uploadMirrorOnlyDocuments] in this same sync, so it usually reaches the device
 * folder a moment later, and the notice that seam owns says the opposite ("the only
 * copy is inside VSCodroid"). What tells the user is stronger than a toast anyway:
 * the file sits in the explorer beside the one it was set aside from. The
 * announcement is left to the upload, which fires it only when the copy really did
 * stay inside the app.
 */
internal fun SafSyncEngine.setAsideDivergedMirror(
    doc: DocumentInfo,
    localPath: File,
    fetched: InboundStage,
    changedDuringFetch: Boolean,
    destination: OutboundPath,
    previouslyRecorded: () -> Map<String, ByteArray?>?,
): Boolean {
    val entry = inboundEntry(destination) ?: return false
    if (!entry.attributes.isRegularFile) return false
    if (!changedDuringFetch) {
        val recorded = previouslyRecorded()
        val identity = identityLine(doc.relativePath, entry.attributes.lastModifiedTime().toMillis(), entry.attributes.size())
        if (recorded != null &&
            matchesRecordedCopy(destination, doc.relativePath, recordLine(identity, recorded[identity]))
        ) return false
    }
    if (sameRootedBytes(destination, destination.leaf, fetched)) return false

    var time = entry.attributes.lastModifiedTime().toMillis()
    var preserved = destination.sibling(localCopyName(localPath.name, time))
    while (!moveToFreeName(destination, localPath, destination.leaf, preserved)) {
        time++
        preserved = destination.sibling(localCopyName(localPath.name, time))
    }
    notePreservedLocalCopy(File(localPath.parentFile, preserved.toString()))
    Logger.i(
        tag,
        "Set the mirror copy of ${doc.relativePath} aside as $preserved: it " +
            "differs from the device copy replacing it and no sync wrote it",
    )
    return true
}


/**
 * Makes room for the device document [doc] where the mirror holds the other kind of
 * entry at its path: a file where the device has a folder, or a folder where it has a
 * file. Matching by name alone, an open otherwise tried to make a directory where a file
 * stood (or copy a file onto a directory), failed on the document and on every child
 * below it, and said so at every open for as long as the entry lived.
 *
 * The device keeps the name. The mirror's entry is set aside under a `.local-` name,
 * exactly as [setAsideDivergedMirror] does for a file both sides changed, and
 * [uploadMirrorOnlyDocuments] puts it across in this same sync, so both are kept and
 * nothing waits for the user. Nothing else of the mirror is touched, and a link is left
 * to the confinement rules that already refuse to write through one.
 *
 * One entry does not become a copy: a file the last sync can vouch for, unchanged since
 * it was read, where the device now holds a folder. That is this app's own copy of
 * what the device replaced, so it follows the device as it does when the device deletes
 * it ([reconcileDeletions] acts on the same evidence); keeping it would put a stale
 * file back on a device folder whose owner replaced it. A mirror folder is never judged
 * this way, because what a folder holds is not one line of the record. A file the
 * record names without a digest is neither shown to be that copy nor shown to be work:
 * it is set aside, and [keepUnplaced] gets a row without a digest for the set-aside
 * copy, which keeps it inside the app as the deletion pass keeps such a copy.
 *
 * Failing to do either leaves the old behaviour: the document cannot be made or
 * copied, and the open reports it. The journal line of a failed save of the entry is
 * retired with it (a folder's lines move with the folder): the path it names is a
 * different kind of entry now, and the set-aside copy's own upload leaves a line of its
 * own if it fails, so no stale line pins the mirror.
 */
internal fun SafSyncEngine.clearKindConflict(
    doc: DocumentInfo,
    localPath: File,
    mirrorRoot: File,
    previouslyRecorded: () -> Map<String, ByteArray?>?,
    keepUnplaced: (String) -> Unit,
) {
    try {
        openOutboundPath(localPath, mirrorRoot).use { destination ->
            val entry = inboundAttributes(destination) ?: return
            val inTheWay = if (doc.isDirectory) entry.isRegularFile else entry.isDirectory
            if (!inTheWay) return
            // A copy the record names without a digest: nothing shows it is the device's old bytes or the
            // person's work, and set aside it is kept inside the app as a deletion keeps it.
            var unproven = false
            if (doc.isDirectory) {
                val recorded = previouslyRecorded()
                val identity = identityLine(doc.relativePath, entry.lastModifiedTime().toMillis(), entry.size())
                unproven = recorded != null && identity in recorded && recorded[identity] == null
                if (recorded != null) {
                    // Through the removal a deletion on the device uses, which takes the entry from
                    // its name before it proves its bytes: proved first and unlinked after, a save
                    // landing between the two went with the unlink and was kept nowhere.
                    val removed = removeRecordedCopy(
                        mirrorRoot, doc.relativePath, recordLine(identity, recorded[identity]),
                    )
                    if (removed == true) {
                        consumeStaleUploadRecordImpl(localPath.absolutePath)
                        Logger.i(
                            tag,
                            "Removed the mirror copy of ${doc.relativePath}: the device replaced " +
                                "the file with a folder",
                        )
                        return
                    }
                    // A copy that could not be read shows neither the recorded copy nor an edit, and
                    // set aside as work it would go back to a device whose owner replaced the file.
                    // It stays where it is, as it did when this read was its own and failed.
                    if (removed == null) {
                        Logger.w(tag, "Could not make room for ${doc.relativePath}: its mirror copy could not be read")
                        return
                    }
                    // Not the recorded copy, which then stands where it was and is set aside below,
                    // or it changed while it was taken and the removal kept it beside its name as a
                    // `.local-` copy and said so. Nothing is in the way then.
                    if (inboundAttributes(destination) == null) {
                        consumeStaleUploadRecordImpl(localPath.absolutePath)
                        return
                    }
                }
            }
            var time = entry.lastModifiedTime().toMillis()
            requireCurrentParent(destination, localPath, mirrorRoot)
            var preservedName = destination.sibling(localCopyName(localPath.name, time))
            while (!moveToFreeName(destination, localPath, destination.leaf, preservedName)) {
                time++
                preservedName = destination.sibling(localCopyName(localPath.name, time))
            }
            requireCurrentParent(destination, localPath, mirrorRoot)
            val preserved = File(localPath.parentFile, preservedName.toString())
            // Not told of as an app edit kept beside its file where it is kept inside the app: that notice reads as
            // work on its way to the device, and the one for files kept inside the app ([keepUnplaced]) says it
            // stays there.
            if (!unproven) notePreservedLocalCopy(preserved)
            if (unproven) {
                keepUnplaced(identityLine(preserved.toRelativeString(mirrorRoot), entry.lastModifiedTime().toMillis(), entry.size()))
            }
            if (doc.isDirectory) consumeStaleUploadRecordImpl(localPath.absolutePath)
            else renameUploadsUnder(localPath, preserved)
            Logger.i(
                tag,
                "Set the mirror ${if (doc.isDirectory) "file" else "folder"} " +
                    "${doc.relativePath} aside as ${preserved.name}: the device holds a " +
                    "${if (doc.isDirectory) "folder" else "file"} under that name",
            )
        }
    } catch (e: Exception) {
        Logger.w(
            tag,
            "Could not make room for ${doc.relativePath}: ${e.javaClass.simpleName}",
        )
    }
}


/**
 * Whether the device document [doc] has changed since the last sync recorded it.
 *
 * The record's line for a path carries the mirror copy's modification time, and
 * [copyDocumentToLocal] stamps that copy with the provider's own time, so for a copy
 * that landed the line holds the device time as of the last sync. A save the watcher
 * delivered is recorded with the mirror copy's time instead, which the provider's stamp
 * on that write never equals, so where the device reports a size that agrees with the
 * line and the line carries the digest of the bytes delivered or fetched, the bytes
 * decide and the time is not asked. Read by time, the next open took this app's own
 * delivered save for another app's edit whenever the mirror had been edited again
 * since, and kept it beside the file as a `.device-` copy with a notice. A line with no
 * digest, as builds up to 1.9.0 wrote, or a provider that reports no size, still goes
 * by the time: a device document reporting any other time has been written since by
 * something other than this app's own sync. The one false positive there is a
 * filesystem that cannot hold the stamp exactly, which `filesDir` never is; it would
 * cost a spare `.device-` copy per reopen, not a byte.
 *
 * A record that parses but does not name the path answers **true**, and so does a
 * line whose time field will not parse. Silence here is not evidence that the device
 * document is ours: the record is silent about a path this app wrote back and never
 * re-read, which is exactly what the caller's own write-back branch leaves behind,
 * and about a document the device gained under a name the mirror also gained.
 * Nothing on disk tells those apart, and one of them is a stranger's bytes. Answering
 * false meant the write-back arm disarmed the guard for every later pass over the
 * same path, so the second foreign edit was destroyed by a `"wt"` open with no copy
 * kept anywhere.
 *
 * True is not free, and the three costs are worth stating because the arm below
 * manufactures the silence that triggers them. One provider read per affected path
 * per open, forever, since nothing here re-records the path. A `.device-` copy kept
 * whenever the bytes differ, which includes the case where the device holds this
 * app's own earlier push and the mirror has been edited again since: that copy is a
 * stale duplicate of the user's own file, and it is the same known false positive
 * [setAsideDivergedMirror] already names for the other two producers of silence.
 * And a fetch that fails withholds the write-back, which defers the user's save
 * rather than losing it. False costs the user someone else's edit outright, which is
 * why true is still the right answer.
 *
 * No record gives no evidence for an overwrite either. The caller preserves the
 * device copy first, while a completed upload can record the stable bytes it delivered.
 */
internal fun SafSyncEngine.deviceChangedSinceRecord(recorded: Map<String, ByteArray?>, doc: DocumentInfo): Boolean {
    val prefix = escapeRecordPath(doc.relativePath) + '\t'
    val line = recorded.keys.firstOrNull { it.startsWith(prefix) } ?: return true
    val parts = line.split('\t')
    val recordedModified = parts.getOrNull(1)?.toLongOrNull() ?: return true
    val recordedSize = parts.getOrNull(2)?.toLongOrNull() ?: return true
    if (doc.size > 0 && recordedSize != doc.size) return true
    val digest = recorded[line] ?: return true
    // A known size that agrees lets the digest decide whatever the time says: a save the
    // watcher delivered records the mirror copy's time, and the provider stamps the write
    // with its own, so the two never agree after one.
    if (doc.size <= 0 && recordedModified != doc.lastModified) return true
    return !digest.contentEquals(deviceSha256(doc.uri, recordedSize))
}


/**
 * Fetches the device copy of [doc] to `<name>.device-<device time>` beside
 * [localPath], and answers what became of it.
 *
 * The counterpart of [setAsideDivergedMirror] for the direction it does not cover:
 * a save the watcher never delivered meeting a device edit made BEFORE it. The
 * mirror copy is newer, so phase 2 writes it over the device document, and the
 * device edit was gone with no copy anywhere; the mirror-side guard cannot fire,
 * because it runs only where the device wins.
 *
 * Fetched rather than renamed, because the copy lives on the other side; that is
 * the one provider read this guard costs, and [deviceChangedSinceRecord] keeps it
 * off every file the record vouches for. The name starts with the device's own time,
 * advanced past local entries and known provider names so earlier preserved bytes
 * and a listed sibling cannot replace each other. The copy retains the source's
 * timestamp so later opens compare times from the same clock.
 *
 * Like the `.local-` copy, this one is a candidate for [uploadMirrorOnlyDocuments]
 * in the same sync, so it usually reaches the device folder a moment later and sits
 * in the explorer beside the file it was set aside from. [keepsDeviceEdit] also calls
 * this while the folder is open, and there the watcher, not phase 2b, puts the copy on
 * the device. The same in-flight guard as phase 2's own copies, and for the same
 * reason: overlapping fetches can choose the same preserved-copy name.
 *
 * The answer is four-valued because the callers ask three different questions of it.
 * "May the mirror go over the device copy now" is yes for [DeviceCopyOutcome.PRESERVED]
 * and for [DeviceCopyOutcome.IDENTICAL]; "is there a `.device-` file in the folder to
 * account for" is yes only for the first; and when the answer is neither,
 * [DeviceCopyOutcome.IN_FLIGHT] holds the save back for [retryHeldBack] without marking
 * the document unread, while [DeviceCopyOutcome.UNAVAILABLE] marks it unread until the
 * next open. A Boolean answered the first, and the counter and the log line that read it
 * as the second described files this had just deleted for being duplicates.
 */
internal fun SafSyncEngine.setAsideDeviceCopy(
    doc: DocumentInfo,
    localPath: File,
    mirrorRoot: File,
    access: OutboundAccess? = null,
): DeviceCopyOutcome {
    // The fetch below lands in `filesDir`, this app's own storage and the thing that
    // runs out, so it obeys the ceiling phase 2's own copies obey. The repair branch
    // that calls this sits ABOVE that gate and reaches a document of any size at all,
    // including one the mirror could never have held; moving the gate up instead
    // would skip the document, leaving the interrupted upload unrepaired and its
    // journal line standing on every later open.
    if (anotherMirrorWritesDocument(doc.uri, mirrorRoot)) {
        return DeviceCopyOutcome.IN_FLIGHT
    }
    if (doc.size > MAX_FILE_SIZE) {
        Logger.i(
            tag,
            "Not setting the device copy of ${doc.relativePath} aside: at " +
                "${doc.size / 1_000_000} MB it is larger than this app's own storage " +
                "will hold a copy of",
        )
        return DeviceCopyOutcome.UNAVAILABLE
    }
    val preserved = try {
        openOutboundPath(localPath, mirrorRoot).use { destination ->
            var time = doc.lastModified
            var name = destination.sibling(deviceCopyName(localPath.name, time))
            while (inboundAttributes(destination, name) != null ||
                docIdCache.containsKey(File(localPath.parentFile, name.toString()).toRelativeString(mirrorRoot))
            ) {
                time++
                name = destination.sibling(deviceCopyName(localPath.name, time))
            }
            File(localPath.parentFile, name.toString())
        }
    } catch (_: Exception) {
        return DeviceCopyOutcome.UNAVAILABLE
    }
    if (!mirrorCopiesInFlight.add(preserved.absolutePath)) {
        return DeviceCopyOutcome.UNAVAILABLE
    }
    // Bounded by the same ceiling as it streams where nothing reported a size, at a stamp
    // query [keepsDeviceEdit] got no answer to ([UNANSWERED_SIZE]): such a document passed
    // the check above whatever it held, and one another app had grown far past the ceiling
    // was fetched in full, the free-space floor asked only before the copy began. Not on a
    // provider that reports no size, whose documents phase 2 fetches whole: stopped at the
    // ceiling there, every set-aside of a file past it that phase 2 had copied in was
    // refused, and the saves of the file with it.
    val limit = if (doc.size == UNANSWERED_SIZE) MAX_FILE_SIZE else Long.MAX_VALUE
    var outcome = DeviceCopyOutcome.PRESERVED
    val fetched = try {
        if (anotherMirrorWritesDocument(doc.uri, mirrorRoot)) {
            return DeviceCopyOutcome.IN_FLIGHT
        }
        copyDocumentToLocal(
            doc.uri, preserved, doc.lastModified, mirrorRoot, limit = limit, access = access,
            expectedSize = doc.size,
            onCopied = { destination, published ->
                if (sameRootedSiblings(destination, destination.sibling(localPath.name), destination.leaf)) {
                    requireCurrentParent(destination, preserved, mirrorRoot)
                    if (destination.attributes().fileKey() != published.fileKey()) {
                        throw IOException("The preserved copy was replaced before cleanup")
                    }
                    destination.parent.deleteFile(destination.leaf)
                    outcome = DeviceCopyOutcome.IDENTICAL
                }
            },
            onReplacing = { _, _, _ -> throw IOException("The preserved-copy name is no longer free") },
        )
    } finally {
        mirrorCopiesInFlight.remove(preserved.absolutePath)
    }
    if (fetched == FailedCopy.IN_FLIGHT) return DeviceCopyOutcome.IN_FLIGHT
    if (fetched !is LandedCopy) return DeviceCopyOutcome.UNAVAILABLE
    // A changed time is not a changed file. This app's own delivered
    // write-back moves the device time and the record never learns the new
    // one, and a provider with coarse stamps (FAT32 at two seconds, cloud
    // providers at whole seconds) reports the write-back's time below the
    // mirror's, so on every reopen after an ordinary save the record differs
    // and the mirror reads newer. The bytes are the same on both sides then,
    // and a copy of them is not a set-aside but a duplicate that phase 2b
    // would put into the user's folder. Compared after the fetch, which is
    // the one provider read this costs either way.
    if (outcome == DeviceCopyOutcome.PRESERVED) {
        val batch = preservedDuringSync.get()
        if (batch == null) onDeviceCopiesPreserved(listOf(preserved)) else batch.add(preserved)
        Logger.i(
            tag,
            "Set the device copy of ${doc.relativePath} aside as ${preserved.name}: " +
                "it differs from the newer mirror copy replacing it and was written " +
                "since the last sync",
        )
    }
    return outcome
}


internal fun SafSyncEngine.reclaimInboundScratch(): File? = synchronized(inboundScratchPaths) {
    val directory = File(context.filesDir, "saf-inbound")
    try {
        try { Files.createDirectory(directory.toPath()) } catch (_: FileAlreadyExistsException) { }
        secureDirectory(directory).use { parent ->
            val identity = parent.getFileAttributeView(BasicFileAttributeView::class.java)
                ?.readAttributes()?.fileKey() ?: throw IOException("Staging has no identity")
            for (entry in parent) {
                val name = entry.fileName
                if (InboundScratchKey(identity, name.toString()) !in inboundScratchPaths) {
                    runCatching { parent.deleteFile(name) }
                }
            }
        }
        directory
    } catch (_: Exception) {
        null
    }
}


internal fun SafSyncEngine.secureDirectory(directory: File): SecureDirectoryStream<Path> {
    val owner = directory.parentFile ?: throw IOException("The directory has no parent")
    val opened = Files.newDirectoryStream(owner.toPath())
    val anchor = opened as? SecureDirectoryStream<Path>
    if (anchor == null) {
        opened.close()
        throw IOException("Filesystem cannot confine staging")
    }
    val handles = mutableListOf(anchor)
    try {
        val child = anchor.newDirectoryStream(directory.toPath().fileName, LinkOption.NOFOLLOW_LINKS)
        handles.add(child)
        anchor.close()
        handles.removeAt(0)
        return child
    } catch (failure: Exception) {
        try { closeOutboundResources(null, handles) } catch (closing: Exception) { failure.addSuppressed(closing) }
        throw failure
    }
}


internal fun SafSyncEngine.createInboundStage(): InboundStage = synchronized(inboundScratchPaths) {
    val directory = reclaimInboundScratch() ?: throw IOException("Inbound staging is unavailable")
    val parent = secureDirectory(directory)
    try {
        val identity = parent.getFileAttributeView(BasicFileAttributeView::class.java)
            ?.readAttributes()?.fileKey() ?: throw IOException("Staging has no identity")
        repeat(16) {
            val name = directory.toPath().fileSystem.getPath("copy-${UUID.randomUUID()}$PARTIAL_SUFFIX")
            try {
                val channel = parent.newByteChannel(name, setOf<OpenOption>(
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS,
                ))
                val key = InboundScratchKey(identity, name.toString())
                inboundScratchPaths.add(key)
                return@synchronized InboundStage(parent, name, key, Channels.newOutputStream(channel))
            } catch (_: FileAlreadyExistsException) { }
        }
        throw IOException("No staging name is available")
    } catch (failure: Exception) {
        try { parent.close() } catch (closing: Exception) { failure.addSuppressed(closing) }
        throw failure
    }
}


internal fun inboundParent(dest: OutboundPath): InboundParent = InboundParent(dest.parentKey())


internal fun SafSyncEngine.parentIsCurrentImpl(dest: OutboundPath, file: File, mirrorRoot: File): Boolean = try {
    openOutboundPath(file, mirrorRoot).use { current ->
        current.rootKey == dest.rootKey && inboundParent(current) == inboundParent(dest)
    }
} catch (_: Exception) {
    false
}


internal fun SafSyncEngine.requireCurrentParent(dest: OutboundPath, file: File, mirrorRoot: File) {
    if (!parentIsCurrent(dest, file, mirrorRoot)) throw IOException("The mirror directory changed")
}


/**
 * Moves [from] to [to], both names in the directory [dest] holds open, unless [to] is taken;
 * false when it is, with nothing moved. [entry] is any entry of that directory, for its path.
 *
 * The move and the refusal are one step ([exclusiveMove]), so an entry another program makes
 * at [to] meanwhile is never replaced: the caller is told the name is taken and goes on to
 * another, or leaves the entry where it is. Where the helper is unavailable, the name is
 * looked at and the move then made, which is what every caller did before and leaves the
 * moment between the two in which such an entry is replaced.
 */
internal fun SafSyncEngine.moveToFreeName(dest: OutboundPath, entry: File, from: Path, to: Path): Boolean {
    val directory = entry.absoluteFile.parentFile
    val outcome = if (directory == null) ExclusiveMove.Outcome.UNAVAILABLE
    else exclusiveMove(directory, dest.parentKey(), from.toString(), to.toString())
    when (outcome) {
        ExclusiveMove.Outcome.MOVED -> return true
        ExclusiveMove.Outcome.TAKEN -> return false
        // The helper was ended before it said. An entry gone from its name and one standing
        // at the other is the move it made; looked for under its old name it would be lost.
        ExclusiveMove.Outcome.UNKNOWN ->
            if (inboundAttributes(dest, from) == null && inboundAttributes(dest, to) != null) return true
        ExclusiveMove.Outcome.UNAVAILABLE -> Unit
    }
    if (inboundAttributes(dest, to) != null) return false
    dest.parent.move(from, dest.parent, to)
    return true
}


internal fun SafSyncEngine.inboundAttributes(dest: OutboundPath, name: Path = dest.leaf): BasicFileAttributes? = try {
    dest.attributes(name)
} catch (_: NoSuchFileException) {
    null
}


internal fun sameInboundAttributes(a: BasicFileAttributes, b: BasicFileAttributes): Boolean =
    a.fileKey() == b.fileKey() && a.lastModifiedTime() == b.lastModifiedTime() &&
        a.size() == b.size() && a.isRegularFile == b.isRegularFile &&
        a.isDirectory == b.isDirectory && a.isSymbolicLink == b.isSymbolicLink &&
        a.isOther == b.isOther


internal fun SafSyncEngine.inboundEntry(dest: File): InboundEntry? {
    val attributes = try {
        Files.readAttributes(dest.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        return null
    }
    if (attributes.fileKey() == null) throw IOException("The destination has no identity")
    val digest = if (attributes.isRegularFile) {
        Files.newInputStream(dest.toPath(), LinkOption.NOFOLLOW_LINKS).use {
            sha256(it, attributes.size())?.takeIf { result -> result.second == attributes.size() }?.first
                ?: throw IOException("The destination changed while reading it")
        }
    } else null
    val after = Files.readAttributes(
        dest.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS,
    )
    if (!sameInboundAttributes(attributes, after)) {
        throw IOException("The destination changed while reading it")
    }
    return InboundEntry(attributes, digest)
}


internal fun SafSyncEngine.inboundEntry(dest: OutboundPath, name: Path = dest.leaf): InboundEntry? {
    val attributes = inboundAttributes(dest, name) ?: return null
    if (attributes.fileKey() == null) throw IOException("The destination has no identity")
    val digest = if (attributes.isRegularFile) {
        dest.input(name).use {
            sha256(it, attributes.size())?.takeIf { result -> result.second == attributes.size() }?.first
                ?: throw IOException("The destination changed while reading it")
        }
    } else null
    if (!sameInboundAttributes(attributes, dest.attributes(name))) {
        throw IOException("The destination changed while reading it")
    }
    return InboundEntry(attributes, digest)
}


internal fun sameStreamBytes(left: InputStream, right: InputStream): Boolean {
    val one = ByteArray(COPY_BUFFER_SIZE)
    val two = ByteArray(COPY_BUFFER_SIZE)
    while (true) {
        val count = readFully(left, one, one.size)
        if (count != readFully(right, two, two.size)) return false
        if (count == 0) return true
        if (!java.util.Arrays.equals(one, 0, count, two, 0, count)) return false
    }
}


internal fun SafSyncEngine.sameRootedBytes(dest: OutboundPath, name: Path, fetched: InboundStage): Boolean = try {
    val attributes = inboundAttributes(dest, name)
    attributes?.isRegularFile == true && attributes.size() == fetched.attributes().size() &&
        dest.input(name).use { left -> fetched.input().use { right -> sameStreamBytes(left, right) } }
} catch (_: Exception) {
    false
}


internal fun SafSyncEngine.sameRootedSiblings(dest: OutboundPath, first: Path, second: Path): Boolean = try {
    val left = inboundAttributes(dest, first)
    val right = inboundAttributes(dest, second)
    left?.isRegularFile == true && right?.isRegularFile == true && left.size() == right.size() &&
        dest.input(first).use { a -> dest.input(second).use { b -> sameStreamBytes(a, b) } }
} catch (_: Exception) {
    false
}


/**
 * Copies a single SAF document to a local file.
 *
 * @return the installed [LandedCopy], or why no copy was installed. A writer observed
 *   during the read, or a save that changed the file as the copy went into place (see
 *   [installFetched]), remains [FailedCopy.IN_FLIGHT] even if it finishes before the
 *   caller handles the result. Other failures answer [FailedCopy.UNAVAILABLE]. The
 *   destination can still hold a local edit, or its preserved copy can be beside it
 *   if installation failed after preservation. The landed identity is taken before
 *   the rename, not from [dest] afterwards, which an editor can have written by then.
 * @param digest updated with every byte fetched, so a caller learns what the copy
 *   holds without reading it again. Meaningful only after a [LandedCopy] answer.
 * @param limit the most bytes the copy may hold. A document holding more is not
 *   copied, after no more than that has been staged, and answers [FailedCopy.UNAVAILABLE].
 * @param expectedSize a positive provider size the completed stream must reach.
 * @param onCopyFailed told whether the failed copy left the original destination
 *   unchanged, so old record metadata cannot vouch for an intervening edit.
 * @param onReplacing preserves conflicting destination bytes before installation.
 *   The default refuses intervening edits to a device-copy destination.
 */
internal fun SafSyncEngine.copyDocumentToLocal(
    docUri: Uri,
    dest: File,
    sourceModified: Long,
    mirrorRoot: File,
    digest: MessageDigest? = null,
    limit: Long = Long.MAX_VALUE,
    access: OutboundAccess? = null,
    expectedSize: Long = 0,
    onCopyFailed: (destinationUnchanged: Boolean) -> Unit = {},
    onCopied: (destination: OutboundPath, installed: BasicFileAttributes) -> Unit = { _, _ -> },
    onReplacing: (fetched: InboundStage, changedDuringFetch: Boolean, destination: OutboundPath) -> Boolean = { _, changed, _ ->
        if (changed) throw IOException("The preserved-copy destination changed during copy")
        false
    },
): DocumentCopyResult {
    // The only floor, and here because both fetches into filesDir pass through this
    // function: phase 2's copy and [setAsideDeviceCopy]. Both turn an unavailable
    // copy into [unfetched] or UNAVAILABLE, so a
    // document held back is guarded exactly like one that failed, unless phase 2 can
    // read that the mirror already holds it. Measured before every fetch rather than
    // latched, so copies resume if space comes back mid-open.
    // Staged outside the workspace and moved into place only once the stream
    // finished. Abandoned copies are reclaimed on the next open. Writing straight
    // to dest would truncate it first, so a copy cut
    // short (by an exception, or by the process being killed mid-stream) would
    // leave a short file carrying a fresh timestamp. That is indistinguishable
    // from an unsaved local edit, and the sync decision has to tell them apart.
    var partial: InboundStage? = null
    var destination: OutboundPath? = null
    var destinationParent: InboundParent? = null
    var destinationEntry: InboundEntry? = null
    var observedDestination = false
    var installed = false
    try {
        val target = openOutboundPath(dest, mirrorRoot)
        destination = target
        destinationParent = inboundParent(target)
        destinationEntry = inboundEntry(target)
        observedDestination = true
        requireCurrentParent(target, dest, mirrorRoot)
        if (usableSpaceOf(dest.parentFile ?: dest) < OPEN_SPACE_FLOOR_BYTES) {
            Logger.d(tag, "Not copying into ${dest.name}: free space is below the floor")
            return FailedCopy.UNAVAILABLE
        }
        if (anotherMirrorWritesDocument(docUri, mirrorRoot)) {
            Logger.w(
                tag,
                "Not copying into ${dest.name}: another folder grant is still writing its device copy",
            )
            return FailedCopy.IN_FLIGHT
        }
        val copied = withStableDocumentRead(docUri) {
            val source = context.contentResolver.openInputStream(docUri)
            if (source == null) {
                // By the mirror file's own name, never the document id: a provider
                // composes that from the user's own path, and this level ships. See
                // SafStorageManager.persistPermission for the whole of that reasoning.
                Logger.w(tag, "No stream for ${dest.name}")
                return@withStableDocumentRead null
            }
            (if (digest != null) DigestInputStream(source, digest) else source).use { input ->
                // Workspace entries must never choose or redirect the scratch file.
                val scratch = createInboundStage().also { partial = it }
                scratch.output.use { output -> copyAtMost(input, output, limit) }
            }
        }
        val fetched = partial ?: return FailedCopy.UNAVAILABLE
        if (copied == null) {
            Logger.i(
                tag,
                "Not copying ${dest.name}: the document holds more than this app's own " +
                    "storage will hold a copy of",
            )
            return FailedCopy.UNAVAILABLE
        }
        if (expectedSize > 0 && copied < expectedSize) {
            throw IOException("The device stream ended before its reported size")
        }
        // Stamp with the source's own time so later syncs compare two timestamps
        // from the same clock rather than a provider's against the filesystem's.
        if (sourceModified > 0) runCatching { fetched.modified(sourceModified) }
        // Read back from the scratch file, which nothing else writes, so the time is
        // the one the filesystem kept.
        val staged = fetched.attributes()
        if (!staged.isRegularFile || staged.fileKey() == null) throw IOException("Staging is not a regular file")
        val landed = LandedCopy(staged.lastModifiedTime().toMillis(), copied)
        // The one moment anything can act on the copy this is about to replace, and
        // the only one where replacing it is already certain: the stream finished, so
        // the caller is not being asked to judge a copy that may yet fail and leave
        // the destination exactly as it was. The fetched bytes are on disk here, so
        // the comparison the caller needs costs no second read of the document.
        return withCurrent(access) {
            // A terminal can replace a workspace parent while the provider read is blocked.
            requireCurrentParent(target, dest, mirrorRoot)
            val currentEntry = inboundEntry(target)
            val changed = if (destinationEntry == null) currentEntry != null else
                currentEntry == null || !destinationEntry.matches(currentEntry)
            if (changed && currentEntry?.attributes?.isRegularFile != true) {
                throw IOException("The destination type changed during copy")
            }
            val moved = currentEntry != null && onReplacing(fetched, changed, target)
            // The callback can compare whole files before preserving a local edit.
            requireCurrentParent(target, dest, mirrorRoot)
            val afterCallback = inboundEntry(target)
            if (moved) {
                if (afterCallback != null) throw IOException("The preserved destination was recreated")
            } else if (currentEntry == null) {
                if (afterCallback != null) throw IOException("The destination appeared before installation")
            } else if (afterCallback == null || !currentEntry.matches(afterCallback)) {
                throw IOException("The destination changed before installation")
            }
            if (!installFetched(fetched, target, dest, afterCallback)) {
                Logger.w(tag, "Not putting the copy of ${dest.name} in place: a save changed it meanwhile")
                return@withCurrent FailedCopy.IN_FLIGHT
            }
            installed = true
            onCopied(target, staged)
            landed
        } ?: FailedCopy.UNAVAILABLE
    } catch (_: DocumentWriteDuringReadException) {
        Logger.w(
            tag,
            "Discarding copy into ${dest.name}: a write overlapped its device read",
        )
        return FailedCopy.IN_FLIGHT
    } catch (e: Exception) {
        // The class rather than the message, and it takes both halves to be
        // redaction at all: the document id names the user's directory, and a
        // provider stream's own exception quotes the URI in its message, so
        // dropping the interpolation alone would put the same string in logcat by
        // the other route.
        Logger.w(tag, "Failed to copy into ${dest.name}: ${e.javaClass.simpleName}")
        return FailedCopy.UNAVAILABLE
    } finally {
        if (!installed) {
            val unchanged = try {
                val target = destination
                val currentEntry = target?.let { inboundEntry(it) }
                target != null && observedDestination && parentIsCurrent(target, dest, mirrorRoot) &&
                    inboundParent(target) == destinationParent &&
                    if (destinationEntry == null) currentEntry == null else
                        currentEntry != null && destinationEntry.matches(currentEntry)
            } catch (_: Exception) {
                false
            }
            onCopyFailed(unchanged)
        }
        partial?.close()
        destination?.let { runCatching { it.close() } }
    }
}


/**
 * Puts the [fetched] copy at [target]'s name, where [replacing] stands and was judged
 * replaceable, or which was free when [replacing] is null. False when the file at the name
 * is no longer the one judged as the copy goes in: that is a save, and it is kept where it
 * is with the copy dropped, and the caller answers [FailedCopy.IN_FLIGHT].
 *
 * A rename replaces whatever stands at its destination, and the look that judged [replacing]
 * comes before it, so an editor's save into the file in between was replaced by the device's
 * older copy, with no copy of it anywhere. So the file at the name is opened for reading
 * first, and the descriptor is checked to be the judged copy, and checked again once the
 * copy has been renamed over the name: the replaced file lives on through the descriptor,
 * so a save written into it in between is still there to read, and goes to
 * `<name>.local-<time>` beside the file, the name every kept copy of the app's own work
 * gets, with the same notice ([keepHeldSave]), or as far as it could be written, with a
 * notice that it may be incomplete. The install is done once the rename is, whatever
 * keeping the save meets. The first check compares the attributes
 * the name carries once the file is open, and the descriptor's length, with those of
 * [replacing], which the caller read to its digest a moment before; a save that keeps
 * the file's time and length passes it, and is found by the second. The second compares
 * the digest of the descriptor's bytes up to [keptCopyDigestBytes], and past it the
 * length alone, which an in-place save of the same length passes. No process is started:
 * a move helper per replaced file, measured at about 65 ms a process on an API 36
 * emulator, made a reopen with 200 changed files take 30 s, to close a moment of
 * microseconds.
 *
 * What this cannot see is a save made after that second check, and one made by renaming a
 * finished file over the name between the last look and the rename, which the rename then
 * replaces; Linux has no rename that refuses to replace what it was not shown. A free name
 * is renamed onto after the caller's look, so a file a program makes at that name in the
 * same moment is replaced too.
 */
internal fun SafSyncEngine.installFetched(fetched: InboundStage, target: OutboundPath, dest: File, replacing: InboundEntry?): Boolean {
    if (replacing == null || !replacing.attributes.isRegularFile) {
        fetched.parent.move(fetched.name, target.parent, target.leaf)
        fetched.moved = true
        return true
    }
    target.parent.newByteChannel(
        target.leaf, setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
    ).use { held ->
        // [replacing] was read to its digest a moment ago, and the name still carrying its attributes once the
        // file is open says the descriptor is that file, unchanged since: read again, it cost each replaced
        // file a further read of the whole old copy for the same answer.
        if (!sameInboundAttributes(replacing.attributes, target.attributes()) || held.size() != replacing.attributes.size()) {
            return false
        }
        // One last look by name, which narrows the moment a save renamed over the name has.
        if (!sameInboundAttributes(replacing.attributes, target.attributes())) return false
        replaceName(dest) { fetched.parent.move(fetched.name, target.parent, target.leaf) }
        fetched.moved = true
        // The device's copy holds the name from here, so the install is done whatever follows; a failure thrown out
        // of here was taken for a copy that never landed. A look at the held file that fails shows no save and
        // no absence of one, so what it holds is kept as a save would be.
        val unchanged = try {
            holdsJudged(held, replacing)
        } catch (_: Exception) {
            false
        }
        if (!unchanged) keepHeldSave(held, target, dest)
    }
    return true
}


/** Whether [held] still carries the bytes of [judged]: its digest, or past the digest budget its length. */
internal fun SafSyncEngine.holdsJudged(held: SeekableByteChannel, judged: InboundEntry): Boolean {
    val length = judged.attributes.size()
    if (held.size() != length) return false
    if (length > keptCopyDigestBytes) return true
    val digest = sha256(Channels.newInputStream(held.position(0)), length) ?: return false
    return digest.second == length && judged.digest?.contentEquals(digest.first) == true
}


/**
 * Writes the save [held] carries, made into a file a newer device copy has just replaced, to
 * `<name>.local-<time>` beside it, made only where that name is free, and says so as every
 * kept copy of the app's own work is said.
 *
 * The save can still be under way: an editor saves by cutting the file and writing it again,
 * and what it writes after the rename lands in the replaced file, which the editor then
 * reloads from the name as the device's copy. So the copy is made again, after a pause,
 * until two copies agree in length and digest and the file is as long as the last, which
 * keeps the save whole rather than as far as it had got ([copySettledSave]).
 *
 * Never throws. The save lives only in [held] by now, which the caller closes, freeing it. A
 * copy that cannot be written in full, a full disk, a quota or an I/O error, stays as far as it
 * got and is kept and told of like a whole one; a copy that cannot be made at all keeps
 * nothing. Either way [onHeldSaveIncomplete] says the save was not kept whole. No second place
 * is tried: the rename into this directory has just worked, so what fails here is space or the
 * disk, which another directory of the same storage does not change.
 */
internal fun SafSyncEngine.keepHeldSave(held: SeekableByteChannel, target: OutboundPath, dest: File) {
    var time = System.currentTimeMillis()
    var kept: Path? = null
    val whole = try {
        while (true) {
            val name = target.sibling(localCopyName(dest.name, time))
            val out = try {
                createKeptSave(File(dest.parentFile, name.toString())) {
                    target.parent.newByteChannel(
                        name,
                        setOf<OpenOption>(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                    )
                }
            } catch (_: FileAlreadyExistsException) {
                time++
                continue
            }
            kept = name
            out.use { copySettledSave(held, it, dest) }
            break
        }
        true
    } catch (e: Exception) {
        // The pause between two copies is a sleep: an interrupt that stopped it is still the thread's.
        if (e is InterruptedException) Thread.currentThread().interrupt()
        Logger.w(
            tag,
            "Could not keep all of a save made into ${dest.name} as the device's copy replaced it" +
                (kept?.let { ", kept what was written as $it" } ?: "") + ": ${e.javaClass.simpleName}",
        )
        false
    }
    // A copy cut short is told of as a kept copy too: unnamed, an open over a record it cannot read gave it a row
    // without a digest and said it may have been deleted on the device, and it is the person's work, which goes
    // across.
    kept?.let { notePreservedLocalCopy(File(dest.parentFile, it.toString())) }
    if (whole) Logger.w(tag, "Kept a save made into ${dest.name} as the device's copy replaced it, as $kept")
    else onHeldSaveIncomplete(dest)
}


/**
 * Copies what [held] carries into [out], from the start each time, until two copies in a row
 * agree in length and digest and [held] is as long as the last, with [heldSaveSettle] between
 * them, or until [HELD_SAVE_WAIT_MS] have passed or [HELD_SAVE_COPY_BYTES] have been copied,
 * when the last copy stands. [out] is cut to each copy's length, since a save can end shorter
 * than what an earlier copy found.
 */
internal fun SafSyncEngine.copySettledSave(held: SeekableByteChannel, out: SeekableByteChannel, dest: File) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HELD_SAVE_WAIT_MS)
    val buffer = ByteBuffer.allocate(COPY_BUFFER_SIZE)
    var copied = 0L
    var last: Pair<Long, ByteArray>? = null
    while (true) {
        val digest = MessageDigest.getInstance("SHA-256")
        var length = 0L
        held.position(0)
        out.position(0)
        while (held.read(buffer) >= 0) {
            buffer.flip()
            digest.update(buffer.duplicate())
            while (buffer.hasRemaining()) length += out.write(buffer)
            buffer.clear()
        }
        out.truncate(length)
        copied += length
        val pass = length to digest.digest()
        if (last != null && last.first == pass.first && last.second.contentEquals(pass.second) && held.size() == length) return
        if (System.nanoTime() >= deadline || copied >= HELD_SAVE_COPY_BYTES) {
            if (last != null) Logger.w(tag, "Kept a save made into ${dest.name} as it stood: it was still changing")
            return
        }
        last = pass
        heldSaveSettle()
    }
}
