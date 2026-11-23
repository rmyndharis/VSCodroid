package com.vscodroid.storage

import com.vscodroid.util.Logger
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread
import com.vscodroid.storage.SafSyncEngine.Companion.UPLOAD_JOURNAL_HEADER
import com.vscodroid.storage.SafSyncEngine.Companion.UNWRITTEN_MARK
import com.vscodroid.storage.SafSyncEngine.Companion.PARTIAL_SUFFIX
import com.vscodroid.storage.SafSyncEngine.Companion.UPLOADS_IN_FLIGHT_FILE
import com.vscodroid.storage.SafSyncEngine.Companion.uploadJournalLock
import com.vscodroid.storage.SafSyncEngine.Companion.uploadClaims
import com.vscodroid.storage.SafSyncEngine.Companion.escapeRecordPath
import com.vscodroid.storage.SafSyncEngine.Companion.unescapeRecordPath
import com.vscodroid.storage.SafSyncEngine.Companion.MAX_FILE_SIZE

// In-flight upload journal and stale upload claim helpers for [SafSyncEngine].

internal fun SafSyncEngine.uploadJournal(): File = File(context.filesDir, UPLOADS_IN_FLIGHT_FILE)


/**
 * Records recovery intent before claiming [absolutePath], or returns null on failure.
 *
 * The claim is handed back rather than looked up again at the end, because the path
 * it names can move while the stream runs; see [UploadClaim].
 */
internal fun SafSyncEngine.markUploadInFlightImpl(absolutePath: String): UploadClaim? =
    synchronized(uploadJournalLock) {
        val nothingSent = recordUploadIntentImpl(absolutePath) ?: return@synchronized null
        UploadClaim(absolutePath, nothingSent).also { uploadClaims.add(it) }
    }


/**
 * Writes the journal line of [absolutePath] before anything reaches the provider, and takes off the mark
 * of a line no write had reached the provider under ([markUnwritten]): this one may, and a process death
 * in the middle has to leave a line that can stand for a cut-short write.
 *
 * Null when the line could not be written; otherwise whether nothing of this file had been handed to the
 * provider under a line before: there was none, or only a marked one.
 */
internal fun SafSyncEngine.recordUploadIntentImpl(absolutePath: String): Boolean? =
    synchronized(uploadJournalLock) {
        try {
            val journal = uploadJournal()
            val (lines, unwritten) = if (journal.isFile) journalEntries(journal) else emptyList<String>() to emptySet()
            val standing = absolutePath in lines
            if (!standing || absolutePath in unwritten) {
                rewriteJournal(if (standing) lines else lines + absolutePath, unwritten - absolutePath)
            }
            !standing || absolutePath in unwritten
        } catch (e: Exception) {
            Logger.w(tag, "Could not record the upload in flight: ${e.message}")
            null
        }
    }


/**
 * Ensures [absolutePath] has a recovery entry without modifying an already standing entry
 * or its unwritten mark.
 *
 * Returns null on journal failure, true when this call inserted a new entry (which the
 * caller owns cleaning up if the paired rename fails), and false when an entry was already
 * standing before this call.
 */
internal fun SafSyncEngine.recordUploadIntentIfAbsent(absolutePath: String): Boolean? =
    synchronized(uploadJournalLock) {
        try {
            val journal = uploadJournal()
            val (lines, unwritten) = if (journal.isFile) journalEntries(journal) else emptyList<String>() to emptySet()
            if (absolutePath in lines) {
                false
            } else {
                rewriteJournal(lines + absolutePath, unwritten)
                true
            }
        } catch (e: Exception) {
            Logger.w(tag, "Could not record the upload in flight: ${e.message}")
            null
        }
    }


/**
 * Marks the journal line of [absolutePath] as one no write has handed a provider stream: every try under it
 * failed before the provider was asked for one, or answered with none, or the create of its document did not
 * happen. The line stays, because the device never got the file and the retry and the open's put-back pass
 * read it. What the mark changes is the one reading that takes the line for this app's own cut-short write
 * ([partlyWrittenUploads]): with nothing of this app's on the device, a document that is a prefix of the
 * mirror file is what another app left, and a save or an open went over it with nothing kept.
 */
internal fun SafSyncEngine.markUnwritten(absolutePath: String) = synchronized(uploadJournalLock) {
    try {
        val journal = uploadJournal()
        if (!journal.isFile) return@synchronized
        val (lines, unwritten) = journalEntries(journal)
        if (absolutePath in lines && absolutePath !in unwritten) rewriteJournal(lines, unwritten + absolutePath)
    } catch (e: Exception) {
        Logger.w(tag, "Could not mark the upload record: ${e.message}")
    }
}


/**
 * Drops this writer's claim, and with it the line only when the mirror file it names
 * went away while the write ran.
 *
 * The counterpart to [clearUploadInFlight], and deliberately not the same
 * function. Both say "this writer is finished"; only one of them says "and the
 * device copy is current now". A failed write-back has to say the first without
 * the second, or the mirror edit it could not deliver is forgotten and the next
 * sync has no reason to retry it.
 *
 * The one line it does take is the one with nothing left to retry. Two retirements
 * defer to a live claim rather than take it, [consumeStaleUploadRecord] for a delete
 * or rename of the file itself and [consumeStaleUploadsUnder] for a directory that
 * left the mirror, and each of them was the last pass that could reach that line
 * while the file was gone. Deferring to a writer that then fails here left it
 * standing in the journal, which a later initial sync reads. The test here is the
 * one [consumeStaleUploadsUnder]
 * applies, the file being there, asked at the moment this writer is the only party
 * that knows the line is now unowned.
 *
 * How long that used to last is worth stating exactly, because the two deferrals
 * differ and only one of them ever healed. A directory departure nobody claimed is
 * never propagated to the device ([handleMirrorEvent] holds it back), so the device
 * still holds the document: the next open of the folder copies it back into the
 * mirror, and the open after that reaches [initialSync]'s repair and consumes the
 * line. That is two whole reopens with the mirror mislabelled in between, and no
 * repair at all where the copy cannot come back, which is a folder never opened
 * again, a document past [MAX_FILE_SIZE], one outside the enumeration cap, or one
 * whose stale old-named copy the user removed on the device first. A delete or rename
 * of the file itself has no such second chance: that one IS propagated, so the device
 * document goes with it and nothing can ever copy it back down.
 *
 * The other two branches are unchanged and both matter: a line whose file is still in
 * the mirror stands, because that is the edit the next sync retries, and a line
 * another writer holds a claim on is left to that writer's own release, for the
 * reason [clearUploadInFlight] gives.
 *
 * A line that stands after a write that [wroteNothing], with no stream handed to it, is
 * marked ([markUnwritten]) when no write before it under the line had one either.
 */
internal fun SafSyncEngine.releaseUploadClaimImpl(claim: UploadClaim, wroteNothing: Boolean = false) = synchronized(uploadJournalLock) {
    uploadClaims.remove(claim)
    if (uploadClaims.any { it.path == claim.path }) return@synchronized
    if (!File(claim.path).exists()) {
        removeJournalLine(claim.path)
    } else if (wroteNothing && claim.nothingSentBefore) {
        // No stream of this write, nor of any before it under the line, reached the provider.
        markUnwritten(claim.path)
    }
}


/**
 * Releases [claim] and removes the journal line only when no other writer holds the
 * path it names now.
 *
 * Paired with [markUploadInFlight] and called by writers alone. A second writer of
 * the same path is reachable whenever a folder is reopened while a drain of the
 * previous session is still streaming: the mirror is named by a hash of the folder,
 * so both writers address one file, and before the claims the first to finish removed
 * the line the other was still inside.
 *
 * The line is named by the claim rather than by the path the writer opened, which is
 * what makes a write that lands during a rename of the directory above it retire its
 * own line: [renameUploadsUnder] moved both records while this one streamed.
 */
internal fun SafSyncEngine.clearUploadInFlight(claim: UploadClaim) = synchronized(uploadJournalLock) {
    uploadClaims.remove(claim)
    if (uploadClaims.none { it.path == claim.path }) removeJournalLine(claim.path)
}


/**
 * Drops a journal line whose reason to exist has gone, without touching any claim.
 *
 * Two callers, and neither of them is a writer. [initialSync] consumes a record left
 * by a process that died, for a path it is about to write itself. [processWriteBack]
 * consumes one when the mirror file the line names has been deleted, which is where
 * a file rename arrives too: the line records an edit that never reached the device,
 * and once the file holding that edit is gone there is no edit left to protect.
 *
 * Not the same operation as [clearUploadInFlight] and deliberately not spelled as
 * one. Treating either of those as releasing a claim would
 * drop a live writer's claim and remove the line while that writer was still
 * streaming, which is the defect the claims exist to prevent, arriving through a
 * different door. A path somebody is writing right now is left alone, and that
 * writer's own release retires the line: [clearUploadInFlight] when the write lands,
 * and [releaseUploadClaim] when it fails onto a path the file has left, which is the
 * state the [processWriteBack] caller defers in. The [initialSync] caller defers in
 * the other one, where the file is there and about to be written out again, so the
 * line it leaves is retired by that write landing.
 */
internal fun SafSyncEngine.consumeStaleUploadRecordImpl(absolutePath: String) = synchronized(uploadJournalLock) {
    if (uploadClaims.any { it.path == absolutePath }) return@synchronized
    removeJournalLine(absolutePath)
}


/** Caller must hold [uploadJournalLock]. */
internal fun SafSyncEngine.removeJournalLine(absolutePath: String) {
    try {
        val journal = uploadJournal()
        if (journal.isFile) {
            val all = readUploadJournal(journal)
            val kept = all.filter { it != absolutePath }
            if (kept.size != all.size) rewriteJournal(kept)
        }
    } catch (e: Exception) {
        Logger.w(tag, "Could not clear the upload record: ${e.message}")
    }
}


/**
 * Moves the upload records of [from] to [to], so they follow a directory that has
 * moved.
 *
 * Two callers, and the same one fact makes both necessary: the records are keyed by
 * absolute mirror path, so a path that moves takes its meaning with it. A removal
 * renames a whole mirror out of the way; a rename in the editor moves one directory
 * inside it, which nothing else in the write-back carries across, because a claimed
 * rename deliberately re-uploads nothing.
 *
 * The journal is keyed by absolute path, and a mirror's directory name is a hash
 * of the tree URI rather than of the session, so the path a removal frees is the
 * path the same folder gets again when the user re-grants it. Without this, the
 * records of the departing mirror and of its replacement are the same strings, and
 * whichever side is cleared takes the other's with it: retiring them loses the live
 * mirror's distrust, and keeping them applies a dead mirror's distrust to a live
 * one. Both end the same way, with a stale copy written over the user's file.
 *
 * Renaming them at the moment the directory moves is what keeps the two apart,
 * because that is the last moment anything can still tell them apart. It survives a
 * process death for the same reason the rename does: both are on disk before
 * anything else happens.
 *
 * The live writers' claims move with the lines, in the same critical section, because
 * they are two records of one write and this runs on a thread of its own: a directory
 * can be renamed in the editor while a write below it is still streaming. Moving only
 * the line would leave that write releasing a name nothing is filed under, so a write
 * that landed would be recorded for ever as one that never reached the device.
 *
 * Which is why the claims move *after* the lines, and not at all when moving the
 * lines threw. The journal half is best effort by design, and it fails in exactly the
 * state that fills the journal in the first place: [rewriteJournal] writes a temporary
 * file in `filesDir`, so internal storage that has run out refuses it. A claim that
 * moved on its own is the same defect from the other side, and permanent rather than
 * retried: the writer would retire a line nobody ever wrote and leave the real one
 * standing under a path that has gone, which nothing can consume afterwards
 * ([consumeStaleUploadsUnder] never sees a claimed departure, and [initialSync]'s
 * repair needs the file the line names to be there), leaving a stale journal line
 * after a save that did reach the device. Both records staying
 * at the old name is the recoverable half of the pair: the writer's release still
 * names the line it is filed under.
 */
internal fun SafSyncEngine.renameUploadsUnderImpl(from: File, to: File) {
    synchronized(uploadJournalLock) {
        val old = from.absolutePath + File.separator
        val new = to.absolutePath + File.separator
        try {
            val journal = uploadJournal()
            // A journal that is not there, and a rewrite with nothing to move, are
            // both moves that succeeded: there is no line to disagree with the claim.
            if (journal.isFile) {
                val (all, unwritten) = journalEntries(journal)
                val move = { path: String -> if (path.startsWith(old)) new + path.removePrefix(old) else path }
                val moved = all.map(move)
                if (moved != all) rewriteJournal(moved, unwritten.mapTo(HashSet(), move))
            }
        } catch (e: Exception) {
            Logger.w(tag, "Could not move the upload records of ${from.name}: ${e.message}")
            return
        }
        uploadClaims.forEach {
            if (it.path.startsWith(old)) it.path = new + it.path.removePrefix(old)
        }
    }
}


/**
 * Moves the provider's unread markers, fetched exceptions, retained deletes, version
 * baselines and the record's rows only after its directory relocation lands. Older
 * queued deletes still need the old paths until then. Failed and superseded renames
 * keep them there. Held saves end because their captured document URIs may no longer be
 * valid; what they were waiting to send is not lost with them: the rename's job takes the
 * holds under the old name before it calls this and sends them at the new one
 * ([processWriteBack]), and a directory made under the name the device stored has its
 * children sent right after ([createInSaf]).
 */
internal fun SafSyncEngine.renameDeviceMemoryUnder(from: File, to: File) {
    val old = from.absolutePath
    val new = to.absolutePath
    if (old == new) return
    renameRecordRowsUnder(old, new)
    fun movedTo(path: String): String? = when {
        path == old -> new
        path.startsWith(old + File.separator) -> new + path.removePrefix(old)
        else -> null
    }
    for (memory in listOf(unfetched, fetchedPaths, keptOnDevice)) {
        for (path in memory.filter { movedTo(it) != null }) {
            val moved = movedTo(path) ?: continue
            memory.add(moved)
            memory.remove(path)
        }
    }
    for (path in deviceSeen.keys.filter { movedTo(it) != null }) {
        val moved = movedTo(path) ?: continue
        val seen = deviceSeen[path] ?: continue
        deviceSeen[moved] = seen
        deviceSeen.remove(path, seen)
    }
    heldBack.keys.removeAll { movedTo(it) != null }
    settledHeldWait.keys.removeAll { movedTo(it) != null }
}


/**
 * Retires the journal lines under [directory] whose mirror file is no longer there.
 *
 * The directory-shaped counterpart to [consumeStaleUploadRecord], and it exists
 * because the delete that retires a line never arrives for the files under a
 * directory that leaves the mirror. [handleMirrorEvent] holds that departure back for
 * a rename to claim rather than queueing anything, inotify raises no event at all for
 * the entries beneath a directory that moved, and [consumeStaleUploadRecord] matches
 * one exact path, so it would not reach them even if it ran. Nothing else could:
 * [initialSync]'s call is guarded by the file still existing, and [clearUploadsUnder]
 * runs only once the whole mirror is going away. One write-back that gave up followed
 * by a directory move no rename claimed therefore left a line standing for the life of
 * the install. A later initial sync reads that journal, so the stale line needs
 * retirement when the path is gone.
 *
 * A line whose file is still there is left alone, and that is the whole distinction:
 * something is at that path again, a directory recreated under the same name or a
 * checkout that restored it, and the line still records that the mirror holds an edit
 * the device never received. So is a line some writer holds a claim on, for the reason
 * [consumeStaleUploadRecord] gives, and taking it here is the defect [UploadClaim]
 * exists to prevent. That deferral is not a hand-off into nothing: the writer applies
 * this same test when it releases, so a write that fails onto the path this pass
 * skipped retires the line itself ([releaseUploadClaim]), and one that lands retires
 * it through [clearUploadInFlight].
 */
internal fun SafSyncEngine.consumeStaleUploadsUnder(directory: File) {
    synchronized(uploadJournalLock) {
        try {
            val journal = uploadJournal()
            if (!journal.isFile) return
            val prefix = directory.absolutePath + File.separator
            val all = readUploadJournal(journal)
            val kept = all.filter { line ->
                !line.startsWith(prefix) || File(line).exists() ||
                    uploadClaims.any { it.path == line }
            }
            if (kept.size != all.size) rewriteJournal(kept)
        } catch (e: Exception) {
            Logger.w(
                tag,
                "Could not retire the upload records under ${directory.name}: ${e.message}"
            )
        }
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
internal fun SafSyncEngine.clearUploadsUnderImpl(mirrorRoot: File) {
    synchronized(uploadJournalLock) {
        try {
            val journal = uploadJournal()
            if (!journal.isFile) return
            val prefix = mirrorRoot.absolutePath + File.separator
            val all = readUploadJournal(journal)
            val kept = all.filterNot { it.startsWith(prefix) }
            if (kept.size != all.size) rewriteJournal(kept)
        } catch (e: Exception) {
            Logger.w(tag, "Could not clear the upload records under ${mirrorRoot.name}: ${e.message}")
        }
    }
}


internal fun SafSyncEngine.uploadsInFlightImpl(): Set<String> = synchronized(uploadJournalLock) {
    try {
        val journal = uploadJournal()
        if (journal.isFile) readUploadJournal(journal).filter { it.isNotBlank() }.toSet()
        else emptySet()
    } catch (e: Exception) {
        // Said rather than swallowed: an unreadable journal silently disables
        // the whole bracket, and that is worth a log line when it happens.
        Logger.w(tag, "Could not read the upload journal: ${e.message}")
        emptySet()
    }
}


/**
 * The journal lines that may stand for this app's own cut-short write: every line but those [markUnwritten]
 * marked, whose writes never handed the provider a stream.
 */
internal fun SafSyncEngine.partlyWrittenUploadsImpl(): Set<String> = synchronized(uploadJournalLock) {
    try {
        val journal = uploadJournal()
        if (!journal.isFile) return@synchronized emptySet()
        val (lines, unwritten) = journalEntries(journal)
        lines.filterTo(HashSet()) { it.isNotBlank() && it !in unwritten }
    } catch (e: Exception) {
        Logger.w(tag, "Could not read the upload journal: ${e.message}")
        emptySet()
    }
}


/** Reads escaped journals while preserving literal backslashes in older journals. */
internal fun SafSyncEngine.readUploadJournal(journal: File): List<String> = journalEntries(journal).first


/**
 * The paths of the journal's lines, and those of them marked [UNWRITTEN_MARK]. A mark is a line of its own,
 * written only in the escaped form, where no path line can begin with it; a journal with no mark is written
 * as it always was.
 *
 * A line is told for a mark after it is unescaped, so a mark reads as one in either spelling. No release up to
 * 1.10.1 knows the mark, so one meets it only when it is installed over this build: it reads a mark as a path
 * and, rewriting the journal for another line, escapes its tab. Back on this build that line read as a path
 * that no retirement ever matches, so it stayed for good, and the line it marked lost its mark. A path is
 * absolute, so none begins with the mark's word.
 */
internal fun SafSyncEngine.journalEntries(journal: File): Pair<List<String>, Set<String>> {
    val lines = journal.readLines()
    if (lines.firstOrNull() != UPLOAD_JOURNAL_HEADER) return lines to emptySet()
    val (marks, paths) = lines.drop(1).map(::unescapeRecordPath).partition { it.startsWith(UNWRITTEN_MARK) }
    return paths to marks.mapTo(HashSet()) { it.removePrefix(UNWRITTEN_MARK) }
}


/** The marked paths of the journal as it stands. Caller must hold [uploadJournalLock]. */
internal fun SafSyncEngine.journalMarks(): Set<String> = uploadJournal().let { if (it.isFile) journalEntries(it).second else emptySet() }


/**
 * Replaces the journal in one step, or leaves the previous content exactly
 * where it was.
 *
 * Caller must hold [uploadJournalLock]. The rename is the atomic half; a
 * truncate-and-write here would lose every bracket at once to a process death
 * in the middle, which is the one failure this file exists to remember across.
 * This protects process interruption, without promising persistence after power loss.
 */
internal fun SafSyncEngine.rewriteJournal(lines: List<String>, unwritten: Set<String> = journalMarks()) {
    val journal = uploadJournal()
    if (lines.isEmpty()) {
        journal.delete()
        return
    }
    val tmp = File(journal.parentFile, journal.name + PARTIAL_SUFFIX)
    // A mark goes with its line and with nothing else, so a line removed takes its mark with it.
    val marked = lines.filter { it in unwritten }.distinct()
    val escaped = marked.isNotEmpty() || lines.any { escapeRecordPath(it) != it }
    val body = if (escaped) {
        listOf(UPLOAD_JOURNAL_HEADER) + lines.map(::escapeRecordPath) + marked.map { UNWRITTEN_MARK + escapeRecordPath(it) }
    } else lines
    tmp.writeText(body.joinToString("\n"))
    if (!tmp.renameTo(journal)) {
        tmp.delete()
        throw IOException("Could not replace the upload journal")
    }
}
