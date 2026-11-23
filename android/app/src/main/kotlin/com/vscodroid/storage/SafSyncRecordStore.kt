package com.vscodroid.storage

import com.vscodroid.util.Logger
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.concurrent.thread
import com.vscodroid.storage.SafSyncEngine.ChangesDuringOpen
import com.vscodroid.storage.SafSyncEngine.Companion.COPY_BUFFER_SIZE
import com.vscodroid.storage.SafSyncEngine.Companion.PARTIAL_SUFFIX
import com.vscodroid.storage.SafSyncEngine.Companion.removalCaptureName
import com.vscodroid.storage.SafSyncEngine.Companion.isRemovalCaptureName
import com.vscodroid.storage.SafSyncEngine.Companion.localCopyName
import com.vscodroid.storage.SafSyncEngine.Companion.anotherMirrorWritesDocument
import com.vscodroid.storage.SafSyncEngine.Companion.mirrorAccess
import com.vscodroid.storage.SafSyncEngine.Companion.SYNCED_RECORD_SUFFIX
import com.vscodroid.storage.SafSyncEngine.Companion.SORTED_RECORD_SUFFIX
import com.vscodroid.storage.SafSyncEngine.Companion.RECORD_HEADER
import com.vscodroid.storage.SafSyncEngine.Companion.escapeRecordPath
import com.vscodroid.storage.SafSyncEngine.Companion.recordLine
import com.vscodroid.storage.SafSyncEngine.Companion.splitRecordLine
import com.vscodroid.storage.SafSyncEngine.Companion.unescapeRecordPath
import com.vscodroid.storage.SafSyncEngine.Companion.KEPT_COPY_DIGEST_BYTES
import com.vscodroid.storage.SafSyncEngine.Companion.shouldOverwriteMirror
import com.vscodroid.storage.SafSyncEngine.Companion.uploadPlan
import com.vscodroid.storage.SafSyncEngine.Companion.sha256
import com.vscodroid.storage.SafSyncEngine.Companion.readFully
import com.vscodroid.storage.SafSyncEngine.Companion.isLink

// Synced record, receipt persistence, and deletion reconciliation helpers for [SafSyncEngine].

/**
 * Removes a `<name>.vscodroid-partial` entry beside a document when every byte it holds is
 * the start of that document's copy in the mirror.
 *
 * Builds up to 1.5.0 streamed a device document into that name before renaming it into place,
 * and a copy cut short left it behind for good. This build neither writes that name nor
 * writes such an entry back, so it only sat in the Explorer. A name alone does not make an
 * entry ours: a workspace file can end the same way. So the test is the content. An entry
 * that is the start of the file beside it adds nothing to that file, so dropping it loses
 * nothing whoever made it; anything else, including a link, a directory, an entry longer
 * than its neighbour or one with no neighbour, is kept.
 *
 * It runs after the copies of this open are in place, so the entry of a document that was
 * not yet in the mirror is compared with the copy fetched a moment ago. Almost no document
 * has one, so the common cost is one stat per document.
 */
internal fun SafSyncEngine.removeRedundantPartials(mirrorDir: File, documents: List<DocumentInfo>): Int {
    var removed = 0
    for (document in documents) {
        if (document.isDirectory) continue
        val relative = document.relativePath
        if (!File(mirrorDir, relative + PARTIAL_SUFFIX).exists()) continue
        try {
            openOutboundPath(File(mirrorDir, relative), mirrorDir).use { path ->
                val partial = path.sibling(path.leaf.toString() + PARTIAL_SUFFIX)
                val before = path.attributes(partial)
                if (isStartOfSibling(path, partial, path.leaf, before)) {
                    // A last look: what was compared must still be what is removed.
                    if (!sameInboundAttributes(before, path.attributes(partial))) return@use
                    path.parent.deleteFile(partial)
                    removed++
                }
            }
        } catch (e: Exception) {
            Logger.w(tag, "Kept a partial copy beside $relative: ${e.message}")
        }
    }
    return removed
}


/**
 * Removes the copy a removal took from its name and was killed before it could delete.
 *
 * [removeRecordedCopy] moves the mirror copy of a document the device deleted to
 * [removalCaptureName] while it proves the bytes, and deletes it after. A process that dies in
 * between leaves that entry for good: its name keeps it from being sent, the row that named the
 * copy is dropped at the next open because nothing is at the name any more, and nothing else looks
 * for it. Builds up to 1.9.0 gave it the name `removed.local-<uuid>`, an ordinary one, so their
 * next open sent it to the device as work only this app had.
 *
 * Such an entry sits in the directory of the row it was taken for, so the search is one listing of
 * each directory holding a row with a digest whose copy may have left its name: the row of a document
 * the device no longer lists as a file whose copy the mirror no longer holds as the row names it, and
 * the row of a document the device lists that this open found nothing at in the mirror ([absent]) or
 * did not record as the row names it ([settled]). Only the directories of rows whose document the
 * device no longer lists used to be looked at, and a copy the person wrote to while the device listed
 * the document again, or one a removal could not give a name of its own after the person wrote to it,
 * stayed under a name that is never sent: [reconcileDeletions] keeps the row of such a copy's document
 * while the entry stands, so that its directory is looked at. Every directory holding a row with a
 * digest was then listed at every open, 80 to 135 ms for 3,000 directories on a desktop JVM, where a
 * folder nothing changed in holds no such row and needs no listing. They all are listed once more over
 * a record no open of this build has sorted ([everyFolder]): an earlier build let a row go while its
 * removal's copy stood, and only a listing of every folder holding a row finds that copy. The rows that decide a removal are those
 * whose document the device no longer lists as a file and whose copy the mirror no longer holds as
 * that row names it: a folder, a link, a file made there since or nothing stands at its path. (A file
 * the device replaced with a folder is taken from its name by the same removal, and the folder is what
 * the open finds there.) An entry goes only when it is a regular file with the length of such a row
 * and the SHA-256 of that row's digest: the bytes of a document the device deleted. Anything else
 * stays, an entry with such a name included, because it may be work, and so does one the device lists:
 * an older build sent its copy there, where it is a document like any other. Runs before
 * [uploadMirrorOnlyDocuments], which would send the older name, and while the rows still stand:
 * phase 3 drops them.
 *
 * @return how many entries it removed.
 */
internal fun SafSyncEngine.discardCutOffRemovalCopies(
    mirrorDir: File,
    previous: Map<String, ByteArray?>,
    documents: List<DocumentInfo>,
    settled: Set<String>,
    absent: Set<String>,
    everyFolder: Boolean,
): Int {
    val onDevice = documents.mapTo(HashSet()) { it.relativePath }
    val filesOnDevice = documents.filterNot { it.isDirectory }.mapTo(HashSet()) { it.relativePath }
    // Every row that has a digest, by its directory, and among them the rows of documents the device
    // no longer lists. The first decide which folders the pass looks into and what a copy that is not
    // removed may be: the recorded bytes of a document, which are no work of the person's; the second,
    // what a copy is removed for.
    val recordedRows = HashMap<File, MutableList<Pair<Long, ByteArray>>>()
    val goneRows = HashMap<File, MutableList<Pair<Long, ByteArray>>>()
    // The directories looked into, those of rows whose copy may have left its name.
    val looked = HashSet<File>()
    // The lengths of the rows without a digest, by directory: an entry of such a length may be the unchanged
    // copy of one, which nothing proves is new work, and sent it would undo a deletion made on the device.
    val unprovenLengths = HashMap<File, MutableSet<Long>>()
    for ((identity, digest) in previous) {
        val fields = identity.split('\t')
        val length = fields.getOrNull(2)?.toLongOrNull() ?: continue
        val path = unescapeRecordPath(fields[0])
        if (path.isEmpty()) continue
        val file = File(mirrorDir, path)
        val parent = file.parentFile ?: continue
        if (digest == null) {
            unprovenLengths.getOrPut(parent) { HashSet() } += length
            continue
        }
        recordedRows.getOrPut(parent) { ArrayList() } += length to digest
        if (path in filesOnDevice) {
            if (path in absent || identity !in settled) looked += parent
            continue
        }
        // A row this build cannot make a path of is left to phase 3, which reads rows the same way.
        // Held only by the copy the row names: a file at its path with that time and length. A
        // folder, a link or a file made since is not that copy, and the entry beside it is no less
        // a removal's leftover for it.
        val held = try {
            val standing = Files.readAttributes(
                file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS,
            )
            standing.isRegularFile &&
                identityLine(path, standing.lastModifiedTime().toMillis(), standing.size()) == identity
        } catch (_: NoSuchFileException) {
            false
        } catch (_: Exception) {
            continue
        }
        if (held) continue
        goneRows.getOrPut(parent) { ArrayList() } += length to digest
        looked += parent
    }
    var removed = 0
    for (directory in if (everyFolder) recordedRows.keys else looked) {
        val rows = goneRows[directory].orEmpty()
        val names = directory.list { _, name -> isRemovalCaptureName(name) } ?: continue
        for (name in names) {
            val capture = File(directory, name)
            try {
                if (capture.toRelativeString(mirrorDir) in onDevice) continue
                openOutboundPath(capture, mirrorDir).use { selected ->
                    val attributes = selected.attributes()
                    if (!attributes.isRegularFile) return@use
                    val sameLength = rows.filter { it.first == attributes.size() }
                    // A copy that cannot be read shows nothing either way and stays as it is.
                    val digest = if (sameLength.isEmpty()) null else (inboundEntry(selected)?.digest ?: return@use)
                    if (digest == null || sameLength.none { it.second.contentEquals(digest) }) {
                        // Bytes that are no deleted document's: a removal takes a copy only while it is the recorded
                        // one, so these were written after, by the person, and exist nowhere else. Unless they are
                        // the recorded bytes of a document the device lists again, which a removal took before the
                        // device undid the deletion: that copy is no work, and stays where it is.
                        if (name.endsWith(PARTIAL_SUFFIX) && attributes.size() !in unprovenLengths[directory].orEmpty()) {
                            val recordedAlike = recordedRows[directory].orEmpty().filter { it.first == attributes.size() }
                            val seen = digest ?: if (recordedAlike.isEmpty()) null else (inboundEntry(selected)?.digest ?: return@use)
                            if (seen == null || recordedAlike.none { it.second.contentEquals(seen) }) {
                                keepCaptureAsLocalWork(selected, capture, mirrorDir)
                            }
                        }
                        return@use
                    }
                    // A last look: what was hashed must still be what is removed.
                    if (!sameInboundAttributes(attributes, selected.attributes())) return@use
                    requireCurrentParent(selected, capture, mirrorDir)
                    selected.parent.deleteFile(selected.leaf)
                    removed++
                }
            } catch (e: Exception) {
                Logger.d(tag, "Kept ${capture.name}: ${e.javaClass.simpleName}")
            }
        }
    }
    return removed
}


/**
 * Gives a copy that a killed removal took from its name, and that holds bytes the person wrote
 * since, a name of its own: `removed.local-<time>`, the shape of every other copy kept beside
 * a file, with the copy's own time. The copy lost the name of its file when the removal took
 * it, so the base is a fixed word. The machine-temporary name it was taken under is never
 * sent, and the row that named the copy is dropped by this open, so the edit stayed in the
 * mirror under a name nobody reads and went with the mirror when it was reclaimed. The new
 * name is an ordinary one: the open creates the file on the device and a notice names it.
 * A time that is taken goes on to the next.
 */
internal fun SafSyncEngine.keepCaptureAsLocalWork(selected: OutboundPath, capture: File, mirrorDir: File) {
    var time = selected.attributes().lastModifiedTime().toMillis()
    var kept = selected.sibling(localCopyName("removed", time))
    requireCurrentParent(selected, capture, mirrorDir)
    while (!moveToFreeName(selected, capture, selected.leaf, kept)) {
        time++
        kept = selected.sibling(localCopyName("removed", time))
    }
    notePreservedLocalCopy(File(capture.parentFile, kept.toString()))
    Logger.w(tag, "Kept a changed copy a removal had taken as $kept")
}


internal fun SafSyncEngine.isStartOfSibling(
    dest: OutboundPath, partial: Path, whole: Path, partialAttributes: BasicFileAttributes,
): Boolean {
    val wholeAttributes = dest.attributes(whole)
    if (!partialAttributes.isRegularFile || !wholeAttributes.isRegularFile ||
        partialAttributes.size() > wholeAttributes.size()) return false
    return dest.input(partial).use { start ->
        dest.input(whole).use { all ->
            val one = ByteArray(COPY_BUFFER_SIZE)
            val two = ByteArray(COPY_BUFFER_SIZE)
            var left = partialAttributes.size()
            while (left > 0) {
                val want = minOf(left, one.size.toLong()).toInt()
                if (readFully(start, one, want) != want || readFully(all, two, want) != want) return false
                if (!java.util.Arrays.equals(one, 0, want, two, 0, want)) return false
                left -= want
            }
            true
        }
    }
}


/**
 * Gives the copies this open kept rather than fetched the digest a fetch leaves, so
 * that [keepsDeviceEdit] can tell a stamp their provider moves afterwards from another
 * app's edit, and puts each digest it reads into [digests] for the copy's record line.
 *
 * Kept means recorded without being fetched: the mirror copy's time equalled the one
 * the device reported, or a read found the device holding the mirror's bytes. With no
 * digest, the first save after such a move kept a `.device-` copy of a document nobody
 * had changed; a FAT card behind the FUSE cache is the ordinary case, reporting a
 * rounded time once its cached inode is evicted. The mirror is what is read, locally,
 * because it holds what the device holds as far as this open can tell, and where it
 * does not, the device's bytes miss the digest and the save goes to the set-aside
 * exactly as it did with none.
 *
 * A copy whose line in the previous record carries a digest, [recordedDigest], takes it
 * from there unread. The line names the copy's time and length, the identity every
 * other reader of the record acts on, so each copy is read for this at most once while
 * its identity stands; reading every kept copy on every open read a typical source tree
 * in full each time. The rest are read newest first, because a provider moves a stamp
 * after a write and the recent copies are the ones it moves, and at most
 * [KEPT_COPY_DIGEST_BYTES] of them per open. Those are the copies of a folder last opened
 * by a build that recorded no digests, and copies recorded by a read that found the
 * device holding them rather than by a fetch, so a folder with more of them than that is
 * read over several opens. What that leaves is a late stamp over a kept copy no open has
 * read yet, past this open's budget, which still keeps one spare copy of the unchanged
 * document.
 */
internal fun SafSyncEngine.digestKeptCopies(
    mirrorDir: File,
    documents: List<DocumentInfo>,
    recorded: List<String>,
    digests: MutableMap<String, ByteArray>,
    recordedDigest: (String) -> ByteArray?,
) {
    val vouched = recorded.toHashSet()
    val recordedFor = recorded.associateBy { it.substringBefore('\t') }
    var budget = keptCopyDigestBytes
    for (doc in documents.sortedByDescending { it.lastModified }) {
        if (doc.isDirectory) continue
        val file = File(mirrorDir, doc.relativePath)
        val seen = deviceSeen[file.absolutePath] ?: continue
        // Not a name that is no regular file any more: opening a pipe waits for a writer,
        // which held the folder's open for as long as none came. Nor a path this open could
        // not read: the write and delete guards stop its saves and deletes before any digest
        // is compared, unless the provider answers them inconsistently, and there a missing
        // digest only keeps the device copy, as a set-aside or a declined delete.
        if (seen.sha256 != null || file.absolutePath in unfetched) continue
        try {
            openOutboundSource(file, mirrorDir).use { selected ->
                val length = selected.attributes.size()
                val line = identityLine(doc.relativePath, selected.attributes.lastModifiedTime().toMillis(), length)
                // Taken whether or not this open vouched for the copy: the record's digest is of
                // the bytes the mirror holds under this time and length, so after a stamp moves
                // it lets a save or a delete go ahead only over those same bytes.
                val recordedOnce = recordedDigest(line)
                val hashed = if (recordedOnce != null) {
                    recordedOnce to length
                } else if (length > budget) {
                    null
                } else {
                    try {
                        selected.input.use { input ->
                            // Vouched for, and asked with the file already open, because the
                            // bytes read are the kept copy's only while the name still is: another
                            // sync over this mirror, an activity recreated mid-open, renames a newer
                            // device copy into place, during phase 2 or after the stat above, whose
                            // digest would match that device copy and skip the set-aside meant to
                            // keep it. A rename after this leaves the open stream on the copy that
                            // was asked about.
                            if (line !in vouched) return@use null
                            budget -= length
                            // Exactly the length the line names, or the digest is not of the copy
                            // it is recorded beside: an editor writing the file meanwhile
                            // lengthens or shortens what the stream reads.
                            sha256(input, length)?.takeIf { it.second == length }
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
                if (hashed == null) return@use
                // Only provider-matching bytes may authorize later deletion.
                val agrees = recordedOnce == null &&
                    !anotherMirrorWritesDocument(doc.uri, mirrorDir) &&
                    hashed.first.contentEquals(deviceSha256(doc.uri, hashed.second))
                if (!sameInboundAttributes(selected.attributes, selected.path.attributes()) ||
                    !parentIsCurrent(selected.path, file, mirrorDir)
                ) return@use
                if (agrees) digests[line] = hashed.first
                // Only over the entry the open left, never one a write has replaced since.
                deviceSeen.replace(
                    file.absolutePath, seen, DeviceState(seen.stamp, hashed.first, hashed.second),
                )
            }
        } catch (_: Exception) {
            // An unreadable or redirected kept copy remains unvouched. One a program deleted after phase 2 kept
            // it has nothing left to open, and without a digest its delete kept the device's copy as changed by
            // another app: it takes the previous record's digest of the copy phase 2 recorded, unread, as a file
            // deleted before the open reached it does, and the delete is checked against the device's bytes.
            if (!file.exists() && !isLink(file)) recordedFor[escapeRecordPath(doc.relativePath)]?.let { line ->
                val digest = recordedDigest(line) ?: return@let
                deviceSeen.replace(file.absolutePath, seen, DeviceState(seen.stamp, digest, line.substringAfterLast('\t').toLong()))
            }
        }
    }
}


/**
 * Removes mirror files for documents that have since been deleted on the device.
 *
 * Reopening a folder used to only create and overwrite, so anything deleted on the
 * device came straight back from the stale mirror, and because a MODIFY on a file
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
 *   anywhere disables deletion. Newly vouched identities are still recorded, with
 *   previous lines retained for paths the partial open did not settle.
 * - Neither does an enumeration that succeeded and returned nothing. A folder the
 *   user emptied and a provider answering for a volume that is no longer mounted
 *   look identical from here, and only one of them costs the whole mirror. The
 *   price of declining is a folder emptied on the device keeping its mirror copies
 *   until they are deleted in the editor, which propagates the other way.
 * - **A candidate must still be the file that was recorded**, matched on modification
 *   time, length and a digest read from a stable file, not merely on its path.
 *   This is the rule the others used to
 *   stand in for, and getting here took four separate defects to notice. Each was a
 *   different way for a path to be recorded while the mirror held something else (a
 *   local edit, a file too large to have ever been copied, a copy that failed), and
 *   each was patched on its own. They were one gap: the record named *paths*, while
 *   the argument for deleting needs *identity*. Matching identity is what makes a
 *   fifth way, which nobody has found yet, stop mattering: a path recorded in error
 *   will not have a file that matches what was recorded for it.
 * - A line that cannot be parsed that way is never a candidate, and neither is any
 *   line of a record that does not open with [RECORD_HEADER]. Records written by an
 *   earlier build are one path per line and carry no identity at all, so they are
 *   unverifiable by construction, and unverifiable has to mean "keep". The header is
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
 * unknown-time branch answers from the record instead, so a diverged file is kept
 * rather than overwritten, and is tracked again once a sync finds the two sides
 * holding the same bytes, before deletion is considered at
 * all. Nothing recorded here can protect what the copy already replaced, and no
 * identity check reaches a file that is gone. The account above of four defects
 * turning out to be one gap holds for providers that report a time, and only those.
 * MTP, some USB-OTG bridges and some network providers do not.
 *
 * A record that could not be read, here or by phase 2 ([recordUnreadable]), is left as
 * it is, and the mirror with it: replaced, every row of a document the device no longer
 * listed lost its digest, and the copy stayed in the app for good. [onWritten] is told
 * when a record is written, which is what the receipts of this open's own saves wait for
 * ([recordDeliveredCopy]); [receipts], those of earlier saves the open could not write before
 * it read the record ([writePendingReceipts]), stand in the rows read here in place of the rows
 * of their paths, as they did in phase 2's. The files this open keeps inside the app ([keptRows]) get rows
 * without a digest in either branch, and [onKept] is given those rows, once: the rows are
 * what keep later opens quiet about them. [unlisted] names the folders the listing could not
 * read (`""` for the whole folder); over a listing that proved nothing, a file under one of
 * them is not kept and gets no row, since the listing says nothing of it.
 *
 * @return how many files were removed.
 */
internal fun SafSyncEngine.reconcileDeletionsImpl(
    mirrorDir: File,
    documents: List<DocumentInfo>,
    enumerationComplete: Boolean,
    recorded: List<String>,
    unplaced: List<String>,
    recordUnreadable: Boolean,
    onWritten: () -> Unit,
    onKept: (List<String>) -> Unit,
    receipts: Collection<String> = emptyList(),
    unlisted: Collection<String> = emptyList(),
): Int {
    if (recordUnreadable) {
        Logger.w(tag, "The record of ${mirrorDir.name} could not be read; leaving it and the mirror as they are")
        return 0
    }
    if (!enumerationComplete || documents.isEmpty()) {
        Logger.w(
            tag,
            "Enumeration proved nothing: leaving the mirror of ${mirrorDir.name} as it is"
        )
        val record = File(mirrorDir.path + SYNCED_RECORD_SUFFIX)
        if (recorded.isNotEmpty() || unplaced.isNotEmpty()) {
            val lines = readSyncedRecord(record) ?: return 0
            val usable = lines.firstOrNull() == RECORD_HEADER
            val settled = recorded.mapTo(HashSet()) { it.substringBefore('\t') }
            // A copy under a folder the listing could not read ([unlisted]) is not one the device lacks: the listing
            // says nothing of it. Given a row, it was told of as a file that may have been deleted in the device
            // folder, which still held it. It gets none, and the record is left unmarked, so the open that can list
            // the folder sorts it as it sorts an earlier build's record: kept and told of if the device has
            // deleted it by then, and never sent for a save the device did not get ([writeSyncedRecord]).
            val placed = unplaced.mapTo(HashSet()) { it.substringBefore('\t') }
            val (unread, kept) = keptRows(mirrorDir, documents, usable, unplaced, settled).partition { row ->
                row.substringBefore('\t') !in placed && unescapeRecordPath(row.substringBefore('\t')).let { path ->
                    unlisted.any { it.isEmpty() || path == it || path.startsWith("$it/") }
                }
            }
            val previous = if (usable) {
                withReceipts(lines.drop(1), receipts).filterNot { it.substringBefore('\t') in settled }
            } else emptyList()
            if (writeSyncedRecordImpl(record, previous + recorded + kept, unsorted = unread.isNotEmpty())) onWritten()
            if (kept.isNotEmpty()) onKept(kept)
        } else if (enumerationComplete && !record.exists() && mirrorDir.list()?.isEmpty() == true) {
            // The first open of a new folder: nothing on the device and nothing in the mirror. A
            // record is left with its header and no rows. Without one, the files made in the folder
            // afterwards have nowhere to record their delivery, and a file whose create died with the
            // process met an open with no record, which cannot tell it from a document the device
            // deleted and kept it for good as a copy it cannot place. Nothing of the device's was ever
            // in this mirror, so there is no deletion to tell it from. Only a mirror with nothing in
            // it: one that holds files is explained by an earlier open, and an empty answer from a
            // volume that is gone must not turn them all into files to send.
            if (writeSyncedRecordImpl(record, emptyList())) onWritten()
        }
        return 0
    }

    val record = File(mirrorDir.path + SYNCED_RECORD_SUFFIX)
    val present = documents.filterNot { it.isDirectory }.map { it.relativePath }.toSet()
    var removed = 0

    val lines = readSyncedRecord(record) ?: return 0
    val usable = lines.firstOrNull() == RECORD_HEADER
    val entries = if (usable) {
        withReceipts(lines.drop(1), receipts)
    } else {
        if (lines.isNotEmpty()) {
            Logger.i(tag, "Record of ${mirrorDir.name} is not this format; removing nothing")
        }
        emptyList()
    }

    val carried = ArrayList<String>()
    // What phase 2b put across this open has a new row in [recorded], and an old one carried
    // beside it would speak against it at the next open.
    val settled = recorded.mapTo(HashSet()) { it.substringBefore('\t') }
    val letGo = ArrayList<Pair<String, String>>()
    for (line in entries) {
        val path = unescapeRecordPath(line.substringBefore('\t'))

        if (path.isEmpty() || path in present) continue
        val gone = removeRecordedCopy(mirrorDir, path, line)
        if (gone == true) removed++
        else if (line.substringBefore('\t') in settled) continue
        // A copy that could not be read keeps its row as well. Let go, the row would leave the
        // copy reading as new local work, and the next open would put a deleted document back.
        else if (gone == null || isUnprovenCopyOfRecord(mirrorDir, path, line)) carried += line
        else letGo += path to line
    }
    // So does a row whose copy has left its name while its directory holds an entry named like a removal's
    // copy: that row is what brings the directory under [discardCutOffRemovalCopies] at the next open, which
    // removes the copy or gives an edit in it a name that is sent. Asked once every removal of this open has
    // run, since a removal later in the loop can leave such an entry beside a row met earlier.
    val holdsRemovalCopy = HashMap<File, Boolean>()
    for ((path, line) in letGo) {
        if (awaitsRemovalCopy(mirrorDir, path, line, holdsRemovalCopy)) carried += line
    }

    val kept = keptRows(mirrorDir, documents, usable, unplaced, settled)
    if (writeSyncedRecordImpl(record, recorded + carried + kept, sorted = true)) onWritten()
    // These files stay inside the app, neither sent nor removed, until each changes: this open could not
    // tell them from a document the device deleted. Said once, now, because the rows just written are what
    // make every later open stay quiet about them.
    if (kept.isNotEmpty()) onKept(kept)
    return removed
}


/**
 * The rows without a digest phase 3 writes for the files this open keeps inside the app: [unplaced], and over
 * a record that is not [usable] every file only the mirror holds ([unprovenRows]), less what this open
 * [settled] with a row of its own.
 */
internal fun SafSyncEngine.keptRows(
    mirrorDir: File,
    documents: List<DocumentInfo>,
    usable: Boolean,
    unplaced: List<String>,
    settled: Set<String>,
): List<String> {
    val unproven = if (usable) emptyList() else {
        unprovenRows(mirrorDir, documents.filterNot { it.isDirectory }.mapTo(HashSet()) { it.relativePath })
    }
    return (unproven + unplaced).filterNot { it.substringBefore('\t') in settled }.distinctBy { it.substringBefore('\t') }
}


/**
 * Whether the row [line] names a copy that has left its name, with a digest, in a directory that holds an entry
 * named like a removal's copy ([isRemovalCaptureName]). A removal that took the copy and could neither put it back
 * nor give an edit in it a name of its own leaves it there, and so does one a process death cut off.
 */
internal fun SafSyncEngine.awaitsRemovalCopy(mirrorDir: File, relativePath: String, line: String, seen: MutableMap<File, Boolean>): Boolean {
    if (splitRecordLine(line).second == null) return false
    val file = File(mirrorDir, relativePath)
    if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
    val directory = file.parentFile ?: return false
    return seen.getOrPut(directory) { directory.list { _, name -> isRemovalCaptureName(name) }?.isNotEmpty() == true }
}


/**
 * Whether the mirror copy of a document that has grown past the size limit is the copy an earlier open
 * made, with no row for it in a record that can be read. Builds up to 1.9.0 dropped the row of such a
 * file at the open that found it too large, and a later deletion of the document on the device put the
 * old copy back there as work only this app had. The copy's time is the time the device reported when
 * the copy was made, so a copy older than the document is that earlier copy. One made or saved after the
 * document last changed is the person's, and has to go to the device when the document is dropped. A
 * record that names the path in another shape (a copy a save has changed since), a link, a file whose
 * upload is still owed and a document that reports no time are none of this: they get no row.
 *
 * Asked only of a record an earlier build wrote. This build lets go the row of a copy that no longer matches
 * it, the person's edit, and the document changing afterwards made that copy older than it: given a row
 * without a digest then, the edit was kept inside the app for good once the device deleted the document.
 * [lines] is read only for a regular file older than the document.
 */
internal fun SafSyncEngine.isOlderCopyOfGrownDocument(doc: DocumentInfo, localPath: File, lines: () -> Map<String, String>?): Boolean {
    if (doc.lastModified <= 0L) return false
    return try {
        Files.isRegularFile(localPath.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            localPath.lastModified() < doc.lastModified &&
            lines()?.let { escapeRecordPath(doc.relativePath) !in it } == true &&
            localPath.absolutePath !in uploadsInFlight()
    } catch (_: Exception) {
        false
    }
}


/**
 * Rows without a digest for the mirror files outside [present], written where the record could
 * not be read. Such a record says nothing about them, and the one written in its place used to
 * leave them out, after which the next open took each for a save the device never got and put
 * a document the device had deleted back there. A row with no digest is what keeps an unproven
 * copy ([isUnprovenCopyOfRecord]): neither removed nor sent while it matches, and sent once it
 * changes. Chosen by [uploadPlan]'s walk, the one phase 2b takes, so what that pass never sends
 * (links, skipped directories, machine-temporary names) gets no row either. A copy this open set
 * aside is work it has just kept rather than a copy it cannot place, and goes across at the next
 * open as before. So is a file the upload journal names, which says the device never got it: a
 * file made in the editor whose create the device refused is one, and the journal is read only
 * for documents the device lists, so with a row it stayed inside the app for good.
 *
 * Over a record an earlier build wrote, only the files [admit] takes: those with no row that are older than
 * the record, which an earlier build may have dropped the row of.
 *
 * A file the person made, saved or moved in while the open ran ([ChangesDuringOpen.placed]) gets no row: the
 * watcher sends it from what the open recorded, and tells of it if it does not ([replayChangesDuringOpen]).
 * With [sortRenamed], one that only came with a folder renamed while the open ran
 * ([ChangesDuringOpen.renamedOnly]) is sorted like any other, under the name the device and the record give it:
 * left to the watcher, which sends whatever the record does not vouch for in such a folder, a copy of a document
 * the device deleted went back to the device under the folder's new name.
 */
internal fun SafSyncEngine.unprovenRows(
    mirrorDir: File,
    present: Set<String>,
    sortRenamed: Boolean = false,
    admit: (file: File, relativePath: String) -> Boolean = { _, _ -> true },
): List<String> {
    val keptThisOpen = (localPreservedDuringSync.get().orEmpty() + preservedDuringSync.get().orEmpty())
        .mapTo(HashSet()) { it.toRelativeString(mirrorDir) }
    val unsent = uploadsInFlight()
    val recording = recordingDuringSync.get()
    val rows = ArrayList<String>()
    val firstName = { file: File -> if (sortRenamed) recording?.renamedOnly(file) else null }
    uploadPlan(mirrorDir, Int.MAX_VALUE) {
        it.isFile && it.absolutePath !in unsent && it.toRelativeString(mirrorDir).let { path ->
            val judged = firstName(it)?.toRelativeString(mirrorDir) ?: path
            judged !in present && path !in keptThisOpen && admit(it, judged)
        }
    }.entries.forEach { file ->
        // Made, saved or moved in by the person while the open ran: the watcher sends it from what the open
        // recorded, so no row keeps it and no notice says it stayed. Kept here, it was announced as left in
        // the app and sent a moment later. The watcher tells of one it does not send.
        if (recording?.placed(file) == true && firstName(file) == null) recording.leaveToReplay(file)
        else rows += identityLine(file.toRelativeString(mirrorDir), file)
    }
    return rows
}


/**
 * Whether [file] came with a folder renamed while the open running on this thread ran
 * ([ChangesDuringOpen.renamedIn]), which leaves it to the replay to send after the rename. Sent by the open, it put
 * the folder on the device under its new name, and the rename the replay makes then had nothing to move to: the
 * device kept the folder under both names.
 */
internal fun SafSyncEngine.renamedInDuringOpen(file: File): Boolean {
    val recording = recordingDuringSync.get() ?: return false
    if (!recording.renamedIn(file)) return false
    recording.leaveToReplay(file)
    return true
}


/**
 * A row with no digest, written before digests existed, past the budget that reads them, or by
 * [unprovenRows] over a record that could not be read, cannot prove the mirror copy is the device's
 * old bytes, so [removeRecordedCopy] keeps the copy. The row has
 * to stay with it: without one the copy reads as new local work, and the next open sends a document the
 * device deleted back to it. A copy that no longer matches its row is work of the user's and is let go.
 * A copy that could not be looked at has shown no such work, so it stays with its row as well; only a
 * failure that shows nothing at the name lets the row go.
 */
internal fun SafSyncEngine.isUnprovenCopyOfRecord(mirrorDir: File, relativePath: String, line: String): Boolean {
    val (whole, digest) = splitRecordLine(line)
    if (digest != null) return false
    // A fourth field this build cannot read leaves the identity in the first three.
    val identity = line.split('\t').let { if (it.size == 4) it.take(3).joinToString("\t") else whole }
    return try {
        openOutboundPath(File(mirrorDir, relativePath), mirrorDir).use { selected ->
            val attributes = selected.attributes()
            attributes.isRegularFile && identity == identityLine(
                relativePath, attributes.lastModifiedTime().toMillis(), attributes.size(),
            )
        }
    } catch (e: Exception) {
        e !is NoSuchFileException && e !is NotDirectoryException
    }
}


/**
 * Removes the mirror copy [line] names while it is still that copy. True when it went, false
 * when what is there is not the recorded copy or nothing is, and null when the copy could not
 * be read or taken from its name, which shows neither: the caller keeps the row, so that the
 * copy does not read as new local work at the next open and go back to a device that deleted
 * it.
 *
 * The entry is taken from its name before its bytes are proved a second time. Bytes written
 * in between are the person's and have lost their name by then, so they are kept beside it
 * as `<name>.local-<time>` and announced, like every other kept edit. No row names that copy,
 * so [uploadMirrorOnlyDocuments] puts it across at the next open and nothing removes it. A
 * second read or a removal that fails with the entry unchanged proves neither, and the entry
 * goes back under its name while that is free, with null as the answer.
 */
internal fun SafSyncEngine.removeRecordedCopy(mirrorDir: File, relativePath: String, line: String): Boolean? {
    val (identity, digest) = splitRecordLine(line)
    if (digest == null) return false
    val file = File(mirrorDir, relativePath)
    var taken = false
    return try {
        openOutboundPath(file, mirrorDir).use { selected ->
            if (!matchesRecordedCopy(selected, relativePath, line)) return@use false
            val before = selected.attributes()
            if (!before.isRegularFile || identity != identityLine(
                    relativePath, before.lastModifiedTime().toMillis(), before.size(),
                )) return@use false
            val preserved = selected.sibling(removalCaptureName())
            requireCurrentParent(selected, file, mirrorDir)
            // Capture the entry before proving its bytes, so a replacement at its old name survives.
            selected.parent.move(selected.leaf, selected.parent, preserved)
            taken = true
            var removed = false
            // Whether what was taken is shown not to be the recorded copy. A failure before
            // that is known shows nothing either way.
            var changed = false
            try {
                val captured = inboundEntry(selected, preserved)
                changed = captured == null || !sameInboundAttributes(before, captured.attributes) ||
                    captured.digest?.contentEquals(digest) != true
                if (!changed) {
                    requireCurrentParent(selected, file, mirrorDir)
                    selected.parent.deleteFile(preserved)
                    removed = true
                }
            } finally {
                val kept = if (removed) null else inboundAttributes(selected, preserved)
                // A read or a removal that failed while the entry stayed as it was taken shows no
                // edit, so the copy goes back under its name, where its row still stands for it.
                // Kept as an edit, it was announced and then put on a device that had deleted it.
                if (kept != null && !changed && sameInboundAttributes(before, kept)) {
                    try {
                        if (moveToFreeName(selected, file, preserved, selected.leaf)) taken = false
                    } catch (_: Exception) { }
                }
                if (kept != null && taken) {
                    // Where even a shortened `<name>.local-<time>` cannot be given, the copy keeps
                    // the name it was taken under and is announced by that.
                    var name = preserved
                    try {
                        var time = kept.lastModifiedTime().toMillis()
                        var free = selected.sibling(localCopyName(file.name, time))
                        while (!moveToFreeName(selected, file, preserved, free)) {
                            time++
                            free = selected.sibling(localCopyName(file.name, time))
                        }
                        name = free
                    } catch (_: Exception) { }
                    notePreservedLocalCopy(File(file.parentFile, name.toString()))
                    Logger.w(tag, "Kept a changed copy of $relativePath as $name")
                }
            }
            removed
        }
    } catch (e: Exception) {
        Logger.d(tag, "Kept $relativePath during deletion: ${e.javaClass.simpleName}")
        // The row goes only when the failure shows nothing at the name, or came after the entry
        // had left it for good. Any other failure left the copy unproved where it was.
        if (taken || e is NoSuchFileException || e is NotDirectoryException) false else null
    }
}


/**
 * Replaces the synced record in one step, or leaves the previous one exactly where
 * it was.
 *
 * Written beside the record and renamed, for the reason [rewriteJournal] gives about
 * the file next to it: a truncate-and-write loses the whole record to a process
 * death in the middle, and the process being killed while a large folder finishes
 * opening is an ordinary event here rather than a rare one. Every consumer of a
 * damaged record does fail safe ([holdsOnlyVouchedCopies] answers no,
 * [reconcileDeletions] removes nothing, [shouldOverwriteMirror]'s unknown-time
 * branch keeps the mirror copy), so what this costs is a mirror that cannot be
 * reclaimed and, on a provider that omits COLUMN_LAST_MODIFIED, a folder that stops
 * receiving device-side changes until [deviceMatchesMirror] heals it a file at a
 * time. The next sync of the same folder rewrites the record in full, so most of the
 * damage lasts one sync, which is exactly as long as the folder goes unopened. What it
 * cannot rebuild is the proof for mirror files the device no longer lists: those get
 * rows without a digest ([unprovenRows]) and stay in the mirror until each changes.
 *
 * The temporary name is deterministic rather than unique, and that is what keeps an
 * interrupted write from leaving one small file per interruption beside the mirror:
 * the next write reuses the same name. That holds only while the folder is opened
 * again, though, and a name `MIRROR_ENTRY` does not match is a name nothing else in
 * `saf-mirrors` ever looks at, so a record whose mirror is then reclaimed used to
 * leave this behind for good, in a directory exported into every terminal as
 * `SAF_MIRRORS_DIR`. `SafStorageManager.reclaimRevokedMirrorsSync` deletes it with
 * the pair it belonged to.
 *
 * Which build wrote the record is told by a mark beside it, `<hash>.sorted` ([sortedMark]),
 * which this build leaves where it made the record, there being none before, and where
 * [sorted] says this write settles the files only the mirror holds, which phase 3 of a
 * complete listing does ([reconcileDeletions]). A record with no mark was last sorted by
 * an earlier build, which may have dropped rows, or left files unsorted ([unsorted]), and
 * [initialSync] sorts it once. Every
 * other write of such a record keeps its time and leaves it unmarked: the open that cannot
 * sort it (a partial or empty listing) or a save delivered meanwhile would otherwise end
 * it, and the next open then sent the copies an earlier build had dropped the rows of. The
 * mark is written after the record, so a process death between the two leaves a record of
 * this build to be sorted once more, never an earlier build's marked. It was the package's
 * update time until this build, which read every record as an earlier build's after every
 * update, this build's own included, and kept the files this build leaves without a row
 * (past the watcher's cap or the open's) inside the app for good. 1.10.1 neither reads nor
 * removes the mark: its reclamation passes over a name it does not know.
 *
 * [unsorted] leaves the record unmarked, and takes the mark away first where one stands: the
 * open that writes it in place of one it could not read, over a listing that could not read
 * every folder, leaves the files only the mirror holds under those folders without a row
 * ([reconcileDeletions]), and the open that can list them sorts them as it sorts an earlier
 * build's record. Marked, the next open took each for a save the device never got and put a
 * document the device had deleted meanwhile back there. Where the mark cannot be taken away,
 * nothing is written.
 *
 * @return whether the record was replaced.
 */
internal fun SafSyncEngine.writeSyncedRecordImpl(record: File, recorded: List<String>, sorted: Boolean = false, unsorted: Boolean = false): Boolean {
    val body = (listOf(RECORD_HEADER) + recorded).joinToString("\n")
    val tmp = File(record.parentFile, record.name + PARTIAL_SUFFIX)
    val mark = sortedMark(record)
    if (unsorted && mark.exists() && !mark.delete()) {
        Logger.w(tag, "Could not take the sorted mark from the synced set; leaving it as it is")
        return false
    }
    val made = !record.exists()
    val earlier = if (sorted || made || mark.exists()) 0L else record.lastModified()
    try {
        tmp.writeText(body)
        if (!tmp.renameTo(record)) {
            throw IOException("Could not replace the synced record")
        }
        if (earlier > 0L) record.setLastModified(earlier)
    } catch (e: Exception) {
        tmp.delete()
        Logger.w(tag, "Could not record the synced set: ${e.message}")
        return false
    }
    if ((sorted || made) && !unsorted) {
        try {
            mark.createNewFile()
        } catch (e: Exception) {
            Logger.w(tag, "Could not mark the synced set as sorted: ${e.message}")
        }
    }
    return true
}


/** The mark beside [record] that this build sorted it; see [writeSyncedRecord]. */
internal fun SafSyncEngine.sortedMark(record: File) =
    File(record.path.removeSuffix(SYNCED_RECORD_SUFFIX) + SORTED_RECORD_SUFFIX)


/**
 * Records only the stable mirror bytes a completed upload delivered.
 *
 * A receipt that cannot go into the record yet waits in [deliveredDuringSync]: one taken while an open
 * runs, which that open records, and one taken over a record that cannot be read, which the next write
 * of the record takes ([withPendingReceipts], [writePendingReceipts]). Dropped, the save had no row, and
 * the next open found the device holding bytes nothing vouched for and kept this app's own save beside
 * the file as another app's edit.
 */
internal fun SafSyncEngine.recordDeliveredCopyImpl(localFile: File, mirrorRoot: File, lastModified: Long, length: Long, sha256: ByteArray) {
    val root = mirrorRoot.absolutePath
    if (!mirrorGenerations.containsKey(root) || !localFile.absolutePath.startsWith(root + File.separator)) return
    fetchedPaths.add(localFile.absolutePath)
    unfetched.remove(localFile.absolutePath)
    val relative = localFile.toRelativeString(File(root))
    val identity = identityLine(relative, lastModified, length)
    val receipt = recordLine(identity, sha256)
    if (mirrorAccess[root]?.syncing == true) {
        deliveredDuringSync[localFile.absolutePath] = receipt
        return
    }
    val record = File(root + SYNCED_RECORD_SUFFIX)
    val lines = readSyncedRecord(record) ?: run {
        deliveredDuringSync[localFile.absolutePath] = receipt
        return
    }
    if (lines.firstOrNull() != RECORD_HEADER) return
    val path = escapeRecordPath(relative)
    // This receipt is newer than any still waiting for the same file.
    deliveredDuringSync.remove(localFile.absolutePath)
    val (rows, taken) = withPendingReceipts(root, lines.drop(1).filterNot { it.substringBefore('\t') == path } + receipt)
    if (writeSyncedRecordImpl(record, rows)) taken.forEach { (file, waiting) -> deliveredDuringSync.remove(file, waiting) }
}


/**
 * [rows] with the receipts still waiting in [deliveredDuringSync] for files under [root] put in place of
 * the rows of the same paths, and those receipts, which the caller takes out once the record is written.
 */
internal fun SafSyncEngine.withPendingReceipts(root: String, rows: List<String>): Pair<List<String>, Map<String, String>> {
    val waiting = deliveredDuringSync.filterKeys { it.startsWith(root + File.separator) }
    return withReceipts(rows, waiting.values) to waiting
}


/** [rows] with each of [receipts] in place of the row of its path. */
internal fun SafSyncEngine.withReceipts(rows: List<String>, receipts: Collection<String>): List<String> {
    if (receipts.isEmpty()) return rows
    val paths = receipts.mapTo(HashSet()) { it.substringBefore('\t') }
    return rows.filterNot { it.substringBefore('\t') in paths } + receipts
}


/**
 * Writes the receipts still waiting for [mirrorDir] ([recordDeliveredCopy]) into its record, so the open
 * about to read it finds the rows of the saves it holds. Answers the receipts it could not write, the
 * record being unreadable still or its write failing, taken out of [deliveredDuringSync] so that the open's
 * own receipts, which it folds into what it records after phase 2, are not mixed with them. The open reads
 * its record with them in place of the rows of their paths ([withReceipts]), as if this write had worked:
 * read without them over a record it could read, it took this app's own delivered save for another app's
 * edit. It puts them back unless the record it writes holds them, on whatever way it ends ([initialSync]).
 */
internal fun SafSyncEngine.writePendingReceipts(mirrorDir: File): Map<String, String> {
    val root = mirrorDir.absolutePath
    val waiting = deliveredDuringSync.filterKeys { it.startsWith(root + File.separator) }
    if (waiting.isEmpty()) return waiting
    val record = File(root + SYNCED_RECORD_SUFFIX)
    val lines = readSyncedRecord(record)?.takeIf { it.firstOrNull() == RECORD_HEADER }
    if (lines != null) {
        val (rows, taken) = withPendingReceipts(root, lines.drop(1))
        if (writeSyncedRecordImpl(record, rows)) {
            taken.forEach { (file, receipt) -> deliveredDuringSync.remove(file, receipt) }
            return emptyMap()
        }
    }
    waiting.forEach { (file, receipt) -> deliveredDuringSync.remove(file, receipt) }
    return waiting
}


/**
 * Moves the record's rows under the mirror path [old] to [new], as a directory rename that
 * landed on the device moved the files they name. Left under the old name, a save the
 * watcher had delivered lost the row that vouches for it, and the next open took the
 * device copy for another app's edit and kept it beside the file as a `.device-` copy.
 * A row already standing under the new name describes a file that is no longer there, but
 * for a row without a digest of a copy that is there as it names it: one an open keeps
 * inside the app ([unprovenRows]) under the name a folder renamed while it ran took, which
 * dropped, left the copy to be sent to the device after the rename ([notVouchedUnder]).
 */
internal fun SafSyncEngine.renameRecordRowsUnder(old: String, new: String) {
    val root = mirrorGenerations.keys.firstOrNull { old.startsWith(it + File.separator) } ?: return
    if (!new.startsWith(root + File.separator)) return
    val from = escapeRecordPath(old.removePrefix(root + File.separator))
    val to = escapeRecordPath(new.removePrefix(root + File.separator))
    fun under(line: String, path: String) =
        line.substringBefore('\t').let { it == path || it.startsWith("$path/") }
    val record = File(root + SYNCED_RECORD_SUFFIX)
    // A record that cannot be read is left as it is, as every other writer of it leaves it.
    val lines = readSyncedRecord(record) ?: return
    if (lines.firstOrNull() != RECORD_HEADER) return
    // Receipts still waiting go in first, so the rows of saves under the old name move with the rest.
    val (rows, taken) = withPendingReceipts(root, lines.drop(1))
    if (rows.none { under(it, from) }) return
    val moved = rows.filter { under(it, from) }.mapTo(HashSet()) { to + it.substringBefore('\t').removePrefix(from) }
    fun keptThere(line: String): Boolean {
        val (identity, digest) = splitRecordLine(line)
        val path = identity.substringBefore('\t')
        return digest == null && path !in moved && File(root, unescapeRecordPath(path)).let {
            it.isFile && identityLine(unescapeRecordPath(path), it) == identity
        }
    }
    val written = writeSyncedRecordImpl(
        record,
        rows.filterNot { under(it, to) && !keptThere(it) }.map { if (under(it, from)) to + it.removePrefix(from) else it },
    )
    if (written) taken.forEach { (file, receipt) -> deliveredDuringSync.remove(file, receipt) }
}


/**
 * Appends [path]'s identity as it stands on disk, or nothing when there is no file
 * there.
 *
 * Read back rather than predicted, so that a timestamp the filesystem truncated or a
 * length the provider misreported cannot put the record out of step with the disk.
 *
 * ⚠️ Reading back is only sound because the caller has established the file is this
 * sync's. It cannot tell on its own: [copyDocumentToLocal] stages the bytes and
 * renames, deliberately leaving the destination untouched when it
 * fails, so after a failed copy this reads whatever was already there, which can be
 * an edit of the user's that no sync ever wrote. Recording that identity is what
 * would make the user's only copy match, and match is what licenses the delete.
 *
 * So the precondition is about the DEVICE, not about who authored the bytes: call
 * this only where the device is known to hold what is on disk here. A comparison that
 * read both sides and found them equal establishes that, which is how a file the
 * editor wrote and the watcher delivered gets recorded without any copy having
 * happened. A landed copy does too, but only for the bytes it landed with, so phase 2
 * records the identity [copyDocumentToLocal] reports rather than reading the file
 * again, which an editor can have written in between.
 */
internal fun SafSyncEngine.recordIdentity(into: MutableList<String>, path: String, attributes: BasicFileAttributes) {
    if (!attributes.isRegularFile) return
    into.add(identityLine(path, attributes.lastModifiedTime().toMillis(), attributes.size()))
}


internal fun SafSyncEngine.recordIdentity(into: MutableList<String>, path: String, file: File, mirrorRoot: File) {
    try {
        openOutboundPath(file, mirrorRoot).use { selected ->
            val attributes = selected.attributes()
            requireCurrentParent(selected, file, mirrorRoot)
            recordIdentity(into, path, attributes)
        }
    } catch (_: Exception) { }
}


/**
 * Whether every file under [mirrorDir] matches an entry in the synced record.
 *
 * Compares the files with the synced record and fails closed if the record is
 * missing, malformed or a directory cannot be read. This predicate does not
 * delete a mirror; launch reclamation leaves mirrors in place. Nothing in the app calls it
 * any more: it states what a record can vouch for, and the tests pin that.
 *
 * Symlinks are not followed: `walkTopDown` reports the link itself, and [isLink]
 * sends it down the not-a-plain-file branch, so a link out of the mirror can neither
 * be vouched for nor be walked through.
 *
 * A temporary-looking workspace name does not establish provenance: a user can
 * create it too, so every regular file must satisfy the same record check. Current
 * inbound staging lives outside the workspace and has its own cleanup.
 */
internal fun SafSyncEngine.holdsOnlyVouchedCopiesImpl(mirrorDir: File): Boolean {
    val lines = readSyncedRecord(File(mirrorDir.path + SYNCED_RECORD_SUFFIX)) ?: return false
    if (lines.firstOrNull() != RECORD_HEADER) return false
    val vouched = lines.drop(1).associateBy { it.substringBefore('\t') }
    // A directory the walk could not read leaves the mirror only partly examined,
    // which is indistinguishable from an empty one to `all`. Recorded rather than
    // returned from the handler: `onFail` is not inline, so it cannot return here.
    var unreadable = false
    return try {
        mirrorDir.walkTopDown().onFail { _, _ -> unreadable = true }.all { file ->
            if (isLink(file)) false
            else if (!file.isFile) true
            else {
                val relative = file.toRelativeString(mirrorDir)
                val line = vouched[escapeRecordPath(relative)]
                line != null && matchesRecordedCopy(file, relative, line) == true
            }
        } && !unreadable
    } catch (e: Exception) {
        Logger.w(tag, "Could not walk ${mirrorDir.name}; treating it as unvouched: ${e.message}")
        false
    }
}


/**
 * Whether [file] is still the copy [line] names, or null when it could not be read. A failed
 * read shows neither a match nor an edit, and the two callers take it differently:
 * [holdsOnlyVouchedCopies] cannot vouch for it, and [uploadMirrorOnlyDocuments] must not
 * take it for the person's newer work.
 *
 * Time and length are compared before any byte is read: a copy they already tell apart is
 * not the recorded one whatever it holds, and read first, a write landing during the read
 * made it a copy that could not be read, so a save the device never got waited for a read
 * no write interrupted.
 */
internal fun SafSyncEngine.matchesRecordedCopy(file: File, relativePath: String, line: String): Boolean? {
    val (identity, digest) = splitRecordLine(line)
    if (digest == null) return false
    val attributes = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        return false
    } catch (_: Exception) {
        return null
    }
    if (!attributes.isRegularFile ||
        identity != identityLine(relativePath, attributes.lastModifiedTime().toMillis(), attributes.size())
    ) return false
    val entry = try {
        inboundEntry(file)
    } catch (_: Exception) {
        return null
    } ?: return false
    return entry.attributes.isRegularFile &&
        identity == identityLine(relativePath, entry.attributes.lastModifiedTime().toMillis(), entry.attributes.size()) &&
        entry.digest?.contentEquals(digest) == true
}


internal fun SafSyncEngine.matchesRecordedCopy(file: OutboundPath, relativePath: String, line: String): Boolean {
    val (identity, digest) = splitRecordLine(line)
    if (digest == null) return false
    val entry = inboundEntry(file) ?: return false
    return entry.attributes.isRegularFile &&
        identity == identityLine(relativePath, entry.attributes.lastModifiedTime().toMillis(), entry.attributes.size()) &&
        entry.digest?.contentEquals(digest) == true
}


/**
 * The last vouched identities, none when there is no file to read, or null when the read
 * failed. A record that could not be read says nothing either way, and read as empty it was
 * replaced: every row of a document the device no longer listed lost its digest, and the copy
 * of each stayed in the app for good. A caller that would write the record writes nothing then.
 *
 * The first line is [RECORD_HEADER]; every line after it is `path`, `modification
 * time` and `length` separated by tabs, the path spelled as [escapeRecordPath]
 * spells it, and a fourth field where an open recorded the copy's digest; see
 * [splitRecordLine]. The header check and the parsing are both the caller's, because a
 * record in a format this build does not know has to be discarded whole and a line
 * it cannot read has to be dropped rather than repaired.
 */
internal fun SafSyncEngine.readSyncedRecord(record: File): List<String>? =
    if (!record.isFile) {
        emptyList()
    } else {
        try {
            record.readLines()
        } catch (e: Exception) {
            Logger.w(tag, "Could not read the synced record: ${e.message}")
            null
        }
    }

// -- File Watching (Write-back) --
