package com.vscodroid.storage

import android.net.Uri
import android.provider.DocumentsContract
import com.vscodroid.util.Logger
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import com.vscodroid.storage.SafSyncEngine.Companion.MAX_ENUMERATION_DEPTH
import com.vscodroid.storage.SafSyncEngine.Companion.CREATED_FILE_MIME_TYPE
import com.vscodroid.storage.SafSyncEngine.Companion.claimProviderMutationWithin
import com.vscodroid.storage.SafSyncEngine.Companion.ownsProviderMutation
import com.vscodroid.storage.SafSyncEngine.Companion.releaseProviderMutation
import com.vscodroid.storage.SafSyncEngine.Companion.withStableDocumentRead
import com.vscodroid.storage.SafSyncEngine.Companion.RECORD_HEADER
import com.vscodroid.storage.SafSyncEngine.Companion.splitRecordLine
import com.vscodroid.storage.SafSyncEngine.Companion.unescapeRecordPath
import com.vscodroid.storage.SafSyncEngine.Companion.parentPathOf
import com.vscodroid.storage.SafSyncEngine.Companion.MAX_WATCHED_DIRECTORIES
import com.vscodroid.storage.SafSyncEngine.Companion.MAX_FILE_SIZE
import com.vscodroid.storage.SafSyncEngine.Companion.UNANSWERED_SIZE
import com.vscodroid.storage.SafSyncEngine.Companion.MAX_UPLOAD_ENTRIES
import com.vscodroid.storage.SafSyncEngine.Companion.shouldOverwriteMirror
import com.vscodroid.storage.SafSyncEngine.Companion.SKIP_DIRECTORIES
import com.vscodroid.storage.SafSyncEngine.Companion.isSafeSegment
import com.vscodroid.storage.SafSyncEngine.Companion.uploadPlan
import com.vscodroid.storage.SafSyncEngine.Companion.sha256
import com.vscodroid.storage.SafSyncEngine.Companion.isLink
import com.vscodroid.storage.SafSyncEngine.Companion.shouldWriteBack

// SAF DocumentsProvider CRUD, tree walking, and URI resolution helpers for [SafSyncEngine].

/**
 * Creates on the device the mirror files the device has no document for, and answers
 * with how many arrived.
 *
 * Until this existed nothing in a sync ever looked at the mirror: phase 2 iterates
 * the DEVICE tree and its only outbound arm needs a device document to compare
 * against, and phase 3 only deletes. So a file that reached the mirror and not the
 * device stayed there for good. It is not hard to produce one: a create whose
 * write-back job died with the process before the drain ran it, a file made in a
 * directory past [MAX_WATCHED_DIRECTORIES] where no observer exists, or one made
 * while [initialSync] itself is running and the watcher is deliberately stopped.
 * The mirror keeps it (launch reclamation never removes a mirror that holds files, and
 * [holdsOnlyVouchedCopies] says the record cannot vouch for such a file), so it is not
 * destroyed, but it lives only
 * in this app's private storage: no other app can see it and it dies with an
 * uninstall. Reopening the folder is what the design calls the way back, and this is
 * what makes that true in the outbound direction.
 *
 * **What it must never do is undo a deletion.** A document the user deleted on the
 * device is absent from the enumeration and still present in the mirror, which is
 * indistinguishable from the case above by anything except the record: phase 3 is
 * about to remove exactly those, and it proves them by the line the last sync wrote.
 * So a path the previous record names is a candidate here only when the mirror's copy
 * no longer matches its row: that is the person's own newer edit, which phase 3 keeps and
 * lets go from the record, so it goes across now rather than at the open after this one. A
 * copy that still matches its row is a deletion made on the device, and phase 3 finishes
 * it. The two passes therefore cannot disagree about one file. A copy that could not be
 * read is not a candidate either: nothing shows an edit, and phase 3 keeps the row of a copy
 * it cannot read, so the open that can read it decides.
 *
 * **A record that did not parse is not a record that names nothing**, and telling
 * those apart is what keeps the rule above from inverting. An absent record, one
 * this build cannot read, and one from the build before [RECORD_HEADER] existed all
 * say nothing about any path, so every mirror file the enumeration did not return
 * would be a candidate and every deletion the user made on the device since that
 * install's last sync would be undone, permanently, on the next open.
 * [reconcileDeletions] cannot offset it either: it declines on the same unusable
 * record, so the file stays in the mirror. This pass writes to the device, so it
 * requires recognized history before adding a missing document. The record that open
 * writes names each such file by a row without a digest, so the opens after it keep
 * the file in the mirror and put it across only once it has changed.
 *
 * That rule is exact for the case it exists for and deliberately not wider. A path
 * the record no longer names, because the last sync kept a local edit there rather
 * than vouching for it, is a candidate again once the device drops its document, and
 * that file IS put back. It is the same file phase 3 keeps in the mirror for the same
 * reason, so the editor goes on showing it either way; the choice is only whether the
 * device folder shows it too, and a copy the user is still editing belongs where they
 * can reach it. Over a record an earlier build wrote, which could have dropped the row
 * of a copy, the older files with no row are [kept] instead: the caller gives them
 * rows without a digest, and phase 3 writes them and says so.
 *
 * A line in the upload journal is the one proof that the device never got a file: a
 * create or a save of it started and did not land. Such a file is sent whatever its
 * time, and where its row has no digest, which proves nothing about the device's copy.
 *
 * [previouslyRecorded] is a function for the reason [shouldOverwriteMirror]'s
 * argument is one. Reading the record costs a parse and a live set, and a healthy
 * folder has no candidate at all, so the read happens only once one is found.
 *
 * [onCandidateDone] is told (done, total) once per candidate, which the caller
 * folds into the progress it is already reporting. It is separate from that
 * callback rather than the same one because the totals are different: the caller
 * counts device documents and this counts mirror files, and only the caller can add
 * the two.
 *
 * [writeLocalToSaf] records a delivered file only after verifying its local bytes.
 * Failed or refused uploads remain unvouched and count toward the shortfall.
 *
 * Two ceilings, both stated rather than hidden. [uploadPlan] stops once it has
 * [MAX_UPLOAD_ENTRIES] files the device does not have and this pass may send, so a
 * mirror with more than that stranded is put across breadth-first and no deeper; and a directory the
 * device does not have is created only when a file below it is going to be put
 * there, so a directory the user deleted on the device does not reappear empty on
 * every open.
 *
 * The cap counts the files this pass would put across, not the entries it walks
 * past, and the difference is what the reopen promise rests on. It used to count
 * every entry: the walk is local and costs the provider nothing, but the cap that
 * bounds [createChildrenInSaf]'s provider round trips was applied to it unchanged,
 * so in any mirror over 2000 entries, an ordinary repository, a file stranded past
 * the first 2000 in breadth-first order was never reached, on that open or any
 * later one, because the walk is deterministic. Counting candidates keeps the bound
 * on provider work, which is one create and one write per candidate, and lets the
 * walk cover the mirror. A file kept inside the app until it changes, by this open
 * ([kept]) or by a row without a digest, is not counted either: a tar into a folder an
 * earlier build last opened leaves thousands, and counted they stood for good in
 * front of every file behind them. Once the cap does bite it is still permanent for that
 * folder until the earlier candidates are put across, since the walk stays
 * deterministic; the shortfall and the cap share one seam, [onShortfall], which the
 * caller folds into one notice for the whole open, and the cap reaches it only with a
 * stranded file in hand, which past the cap is always. [createChildrenInSaf] announces
 * the cap either way, because there the copy is work the user just asked for. The same
 * seam hands over the files left out over an empty listing, by name, which the caller
 * tells of in the notice for files kept inside the app ([onFilesKeptInApp]).
 */
internal fun SafSyncEngine.uploadMirrorOnlyDocuments(
    safUri: Uri,
    mirrorDir: File,
    onDevice: Set<String>,
    previouslyRecorded: () -> Set<String>?,
    madeAfter: Long?,
    kept: Set<String>,
    onShortfall: (lost: Int, capped: Boolean, leftOut: List<String>) -> Unit,
    onCandidateDone: (Int, Int) -> Unit,
): Int {
    // Read once, and only once the walk meets a file the device lacks: phase 2 settled every entry the device
    // answers for, and on a healthy folder that is every entry, so the record's lines are never built.
    val rowsByPath by lazy { previouslyRecorded()?.associateBy { unescapeRecordPath(it.substringBefore('\t')) } }
    // A line in the upload journal says a create or save of the file started and never landed, so the
    // device never got this version: proof enough to send it where its row has no digest to prove the
    // device's copy, or where it has no row. A row with a digest that the copy still matches is a deletion
    // made on the device, whatever was in flight.
    val unsent by lazy { uploadsInFlight() }
    // A file this open keeps inside the app ([kept]) or that a row without a digest keeps there while the copy is
    // the one the row names is never sent by the candidates below until it changes, and spends none of the cap.
    // Counted, a folder with more of them than the cap, as a tar or a `cp -p` into a folder an earlier build last
    // opened leaves, never reached a file behind them that only the app holds, at that open or any later one,
    // and said nothing. It asks what the candidates below ask, so it leaves out only files they never send. A file
    // [leftOut] names still counts, since the shortfall reports it by name: it has no row, and [kept] is filled
    // only over a listing that is not empty, while [leftOut] answers only over an empty one.
    fun keptForGood(original: File, relativePath: String): Boolean {
        if (original.absolutePath in unsent) return false
        val lines = rowsByPath ?: return false
        val line = lines[relativePath] ?: return relativePath in kept
        return splitRecordLine(line).second == null && isUnprovenCopyOfRecord(mirrorDir, relativePath, line)
    }
    // Filtered inside the walk rather than after it, so that an entry the device already answers for, or that
    // stays inside the app for good, spends none of the cap.
    val plan = uploadPlan(mirrorDir, MAX_UPLOAD_ENTRIES) {
        it.isFile && it.toRelativeString(mirrorDir).let { path -> path !in onDevice && !keptForGood(it, path) }
    }
    // Logged even where the returns below leave first, because the record can still
    // refuse everything the walk found, and then the entries past the cap are exactly
    // the ones nobody looked at. The log, and not the notice channel: a mirror with
    // more than the cap stranded is rare and the pass is already about to say so
    // through [onShortfall] for the files it does have in hand.
    if (plan.truncated) {
        Logger.w(
            tag,
            "Stopped at $MAX_UPLOAD_ENTRIES files only the mirror has that it may send; the rest " +
                "stay in the mirror until these are on the device",
        )
    }

    val missing = plan.entries.map { it to it.toRelativeString(mirrorDir) }
    if (missing.isEmpty()) return 0

    // An unusable record cannot tell a save that never reached the device from a
    // document the user deleted there, and this pass writes to the device, so it
    // declines rather than guessing. Said at warn: the folder keeps files that only
    // the mirror holds, which is the state this pass exists to end. The record this
    // sync writes still cannot tell them apart, so it names them without a digest and
    // a later open sends only the ones that change.
    val recordedLines = rowsByPath ?: run {
        Logger.w(
            tag,
            "Not putting files onto ${mirrorDir.name}: its record is missing or in a " +
                "format this build cannot read, so a stranded save and a deletion " +
                "made on the device look the same from here",
        )
        return 0
    }
    // A recorded file stays out while the mirror still holds the copy its row names, which is
    // a deletion made on the device and phase 3's to finish. A copy that no longer matches
    // its row is the person's work, a save the device never got, which phase 3 keeps and lets
    // go from the record: it goes across now rather than at the open after this one. A copy that
    // could not be read is neither yet: a failed read shows no edit, and putting the file back on
    // it undoes a deletion with the bytes the device dropped. It waits for an open that reads it.
    // Over an empty listing, of the files with no row and no such line, only what was made after the
    // record was written: a copy of a document the device deleted, whose row an older build dropped, is
    // not told apart from it any other way. What is left out stays in the mirror and is not sent, so it
    // is told of, by name, as a file kept inside the app: a file whose time was kept when it was put there
    // (unzip, tar, cp -p) is left out the same way. [kept] names the files the open keeps with a row of
    // their own for the same reason, which phase 3 tells of.
    // A file that came with a folder renamed while the open ran is not among them: the watcher sends the folder
    // whole once it starts, the device holding no folder of either name, and said to stay in the app, it was sent.
    fun leftOut(original: File, relativePath: String) = relativePath !in recordedLines &&
        original.absolutePath !in unsent && madeAfter != null && original.lastModified() <= madeAfter &&
        recordingDuringSync.get()?.renamedIn(original) != true
    val olderThanRecord = missing.filter { (original, relativePath) -> leftOut(original, relativePath) }.map { it.second }
    val candidates = missing.filter { (original, relativePath) ->
        val line = recordedLines[relativePath]
        if (original.absolutePath in unsent && (line == null || splitRecordLine(line).second == null)) return@filter true
        if (line == null) return@filter relativePath !in kept && !leftOut(original, relativePath) && !renamedInDuringOpen(original)
        matchesRecordedCopy(original, relativePath, line) == false &&
            !isUnprovenCopyOfRecord(mirrorDir, relativePath, line)
    }
    if (candidates.isEmpty()) {
        if (olderThanRecord.isNotEmpty()) onShortfall(0, plan.truncated, olderThanRecord)
        return 0
    }

    // Seeded with the root, which [resolveDocumentUri] answers for without asking
    // the provider anything. Every directory this pass resolves or creates is
    // remembered here, so a directory holding twenty stranded files is looked up
    // once rather than twenty times.
    val directories = mutableMapOf<String, Uri?>("" to resolveDocumentUriImpl(safUri, ""))
    val movedDirectories = mutableMapOf<String, String>()
    var made = 0
    candidates.forEachIndexed { index, (original, relativePath) ->
        val parent = deviceDirectoryFor(
            safUri, mirrorDir, parentPathOf(relativePath), onDevice, directories, movedDirectories,
        )
        val file = movedMirrorFile(original, movedDirectories)
        if (parent != null && createOneInSaf(file, parent, safUri, mirrorDir)?.delivered == true) made++
        // Every candidate, including one that did not land: this is the folder
        // dialog's only sign of life through a stretch that is one createDocument
        // plus one provider write per file, behind a dialog the user cannot cancel.
        // Phase 2's own count has already reached its total by here, so the tick
        // carries its own denominator; without it the dialog reads "N of N" for as
        // long as this pass runs, which on a mirror full of stranded saves is the
        // longest part of opening the folder.
        onCandidateDone(index + 1, candidates.size)
    }
    Logger.i(
        tag,
        "Put $made of ${candidates.size} file(s) only ${mirrorDir.name} had onto the " +
            "device folder",
    )
    // One notice for the open, not one per file, and spelled exactly as
    // [createChildrenInSaf] spells it for the identical situation. That is the
    // reason [onUploadIncomplete] exists at all: a provider that refuses one create
    // refuses them all, and forty toasts of "the only copy is inside VSCodroid",
    // each three and a half seconds long behind a dialog the user cannot cancel, is
    // another way of telling them nothing. The cap rides along here rather than at
    // the walk itself because this is the point where the pass has a stranded file
    // in hand, which is what makes "there may be more below the cap" worth a
    // sentence instead of noise on every open.
    val lost = candidates.size - made
    if (lost > 0 || plan.truncated || olderThanRecord.isNotEmpty()) onShortfall(lost, plan.truncated, olderThanRecord)
    return made
}


/**
 * The device document for the mirror directory at [relativePath], creating it and
 * whatever is missing above it, or null when the provider would not.
 *
 * Memoised in [resolved] across one pass, negatives included: a parent the provider
 * refused refuses every file under it too, and asking again is a binder walk per
 * segment for an answer already known.
 *
 * A directory the enumeration returned is resolved rather than created, which is
 * what [onDevice] is for: [docIdCache] already holds it, so that costs nothing,
 * while asking the provider to create a name it already has is a round trip whose
 * answer is the same document.
 */
internal fun SafSyncEngine.deviceDirectoryFor(
    safUri: Uri,
    mirrorDir: File,
    relativePath: String,
    onDevice: Set<String>,
    resolved: MutableMap<String, Uri?>,
    movedDirectories: MutableMap<String, String>,
): Uri? {
    if (relativePath in resolved) return resolved[relativePath]
    val uri = if (relativePath in onDevice) {
        resolveDocumentUriImpl(safUri, relativePath)
    } else {
        deviceDirectoryFor(
            safUri, mirrorDir, parentPathOf(relativePath), onDevice, resolved, movedDirectories,
        )?.let { parent ->
            val original = File(mirrorDir, relativePath)
            createOneInSaf(movedMirrorFile(original, movedDirectories), parent, safUri, mirrorDir)?.let {
                movedDirectories[original.absolutePath] = it.localFile.absolutePath
                it.uri
            }
        }
    }
    resolved[relativePath] = uri
    return uri
}


/**
 * Recursively walks a SAF document tree, collecting [DocumentInfo] entries.
 *
 * @return whether every directory under [parentDocId] could be enumerated. A false
 *   here is what stops [reconcileDeletions] from reading a provider that stopped
 *   answering as a device folder that was emptied.
 */
internal fun SafSyncEngine.walkTreeImpl(
    treeUri: Uri,
    parentDocId: String,
    parentRelPath: String,
    result: MutableList<DocumentInfo>,
    skipped: MutableList<String>,
    ancestors: Set<String> = emptySet(),
): Boolean {
    if (parentDocId in ancestors || ancestors.size >= MAX_ENUMERATION_DEPTH) {
        skipped.add(parentRelPath)
        Logger.w(tag, "Not following a repeated or overly deep device directory: $parentRelPath")
        return false
    }
    val branch = ancestors + parentDocId
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
    var complete = true

    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED
    )

    val entries = ArrayList<DocumentInfo>()
    val repeatedNames = LinkedHashSet<String>()

    try {
        val listed = context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            // Not every provider fills this one, so a missing column is not fatal.
            val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            // A provider backed by a network answers with the rows it has and says more
            // are coming, or says it failed. The rows are real, and they are not all of
            // them: read as the whole folder, what they leave out looked deleted on the
            // device and its mirror copy was removed, or looked missing and was created
            // again. Treated as the listing that failed is treated, which is the
            // machinery for exactly this: the folder is recorded as unread, so what is
            // under it stays guarded, and nothing is deleted or put across on its word.
            if (cursor.extras?.let {
                    it.getBoolean(DocumentsContract.EXTRA_LOADING) ||
                        it.containsKey(DocumentsContract.EXTRA_ERROR)
                } == true
            ) {
                complete = false
                skipped.add(parentRelPath)
            }

            // Names this directory lists more than once: a drive that allows two documents
            // of one name in a folder. A path names one file in the mirror and cannot
            // name two, so an open copied the later row over the earlier, and a mirror
            // newer than the older document uploaded one document's bytes over the
            // other's with no copy kept.
            val seenNames = HashSet<String>()

            while (cursor.moveToNext()) {
                val docId = cursor.getString(idIndex)
                val name = cursor.getString(nameIndex)
                val mimeType = cursor.getString(mimeIndex)
                // A null reads back as 0, and a negative size, the -1 some providers give
                // for one they do not know, is read as 0 as well. Taken as a length, it had
                // [deviceMatchesMirror] answer every file unlike the mirror unread, and had a
                // set-aside take it for [UNANSWERED_SIZE] and stop at [MAX_FILE_SIZE] where
                // phase 2 fetches the document whole. [deviceStamp] reads the column alike.
                val size = cursor.getLong(sizeIndex).coerceAtLeast(0)
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

                // Directories only, and only the auto-generated names in
                // [SKIP_DIRECTORIES]: a file is never skipped here, whatever it is
                // called. Filtering hidden names is the tempting addition and is the
                // rule [shouldWriteBack] had to shed, because it kept `.vscode/`,
                // `.gitignore` and `.editorconfig` off the device silently. Note the
                // set holds `.env` as a DIRECTORY name, so a `.env` file is mirrored
                // and a `.env` directory is not.
                if (shouldSkip(name, isDir)) {
                    // Collected, not merely dropped. Nothing under a skipped
                    // directory becomes a DocumentInfo, so it never reaches phase
                    // 2 and never reaches an `unfetched.add`; the DELETE guard
                    // then proves "the device holds the only copy" from a set
                    // that cannot contain it, answers false, and lets
                    // deleteDocument take the whole directory on the device,
                    // including the `.git` or `node_modules` that being skipped
                    // is the reason the mirror never held. The MOVED_FROM hold
                    // already refuses to lose this exact content; this is what
                    // lets the outright delete refuse it too.
                    skipped.add(relativePath)
                    continue
                }

                if (!seenNames.add(name)) {
                    repeatedNames.add(name)
                    continue
                }

                entries.add(DocumentInfo(docUri, docId, relativePath, isDir, size, lastModified))
            }
            true
        }
        if (listed == null) {
            skipped.add(parentRelPath)
            return false
        }
    } catch (e: Exception) {
        skipped.add(parentRelPath)
        // The path inside the folder, not the document id, which the platform
        // provider spells as the user's own directory; and the exception's class,
        // because a provider quotes the document URI in its message.
        Logger.w(
            tag,
            "Failed to enumerate " +
                (if (parentRelPath.isEmpty()) "the folder root" else parentRelPath) +
                ": ${e.javaClass.simpleName}",
        )
        return false
    }

    // Cursor is closed before caching or descending so at most one CursorWindow is held open
    // at any depth, and duplicated names are filtered out before populating docIdCache.
    val repeatedPaths = if (repeatedNames.isNotEmpty()) {
        complete = false
        repeatedNames.mapTo(HashSet()) { name ->
            val path = if (parentRelPath.isEmpty()) name else "$parentRelPath/$name"
            skipped.add(path)
            forgetCachedSubtree(treeUri, path)
            path
        }.also {
            Logger.w(
                tag,
                "Left ${repeatedNames.size} names alone in " +
                    (if (parentRelPath.isEmpty()) "the folder root" else parentRelPath) +
                    ": the provider lists two documents under each",
            )
        }
    } else {
        emptySet()
    }

    for (entry in entries) {
        if (entry.relativePath in repeatedPaths) continue
        // Cache the docId so write-back resolves this path without walking the tree again.
        cacheDocId(treeUri, entry.relativePath, entry.docId)
        result.add(entry)
        if (entry.isDirectory && !walkTreeImpl(treeUri, entry.docId, entry.relativePath, result, skipped, branch)) {
            complete = false
            // Recorded for the same reason a skipped directory is, and it
            // is the same loss: the children could not be enumerated, so
            // none of them becomes a DocumentInfo, none reaches phase 2,
            // and none reaches an `unfetched.add`. Without this the DELETE
            // guard proves "the device holds the only copy" from a set that
            // cannot contain them and lets deleteDocument take the whole
            // directory on the device, including documents the mirror never
            // held. Unlike a skipped name, nothing filters this one out
            // earlier: [shouldWriteBack] drops SKIP_DIRECTORIES by name,
            // and a directory whose enumeration merely failed has an
            // ordinary one.
            skipped.add(entry.relativePath)
        }
    }
    return complete
}


/**
 * Puts a locally created file into the SAF tree, writing into the document that
 * already carries that name rather than adding a second one.
 *
 * [DocumentsContract.createDocument] does not merge: handed a name the folder
 * already has, a provider invents "notes (1).txt" and the user's file quietly
 * forks. Two ordinary things arrive here as a create: the rename
 * [copyDocumentToLocal] performs to move a finished copy into place, and any local
 * rename over an existing name, which is what `mv` and a git checkout do. Resolving
 * first is also what makes the MODIFY-on-unknown fallback in [processWriteBack]
 * safe to keep.
 */
internal fun SafSyncEngine.createInSafImpl(
    localFile: File, parentSafUri: Uri, treeUri: Uri, mirrorRoot: File,
    access: OutboundAccess? = null, mutation: ProviderMutation? = null,
) {
    if (isLink(localFile)) return
    val created = createOneInSaf(localFile, parentSafUri, treeUri, mirrorRoot, access, mutation)
    if (created == null) {
        // The provider refused, and the file exists only inside the app. Exactly
        // what onWriteBackFailed already means, so it says it rather than a second
        // wording of the same fact. Until this line the caller returned on the null
        // and the user was never told: a file they created in the editor simply was
        // not on the device, and the difference only shows after an uninstall.
        announceLost(localFile)
        return
    }
    if (created.localFile.isDirectory) {
        // A directory that just appeared brings whatever is inside it; without
        // this it landed on the device empty and its files stayed in the mirror.
        // What it cannot do is stand in for a copy that already exists on the
        // device: it stops at [MAX_UPLOAD_ENTRIES] and excludes whatever
        // [SKIP_DIRECTORIES] kept out of the mirror. That is why a rename does not
        // come through here at all when [handleMirrorEvent] can pair its two halves
        // it renames the device's document instead, and why the old name is
        // never deleted when it cannot.
        createChildrenInSaf(
            created.localFile, created.uri, treeUri, mirrorRoot, access, mutation = mutation,
            movedHere = writeBackSession.get()?.let(::queuedRenames).orEmpty(),
        )
    }
}


/**
 * Creates or opens the document for [localFile] under [parentSafUri], writing its
 * bytes if it is a file, and returns the document's URI.
 *
 * Does not descend. [createInSaf] owns that, so that the recursion lives in one
 * place and this stays the single-entry operation both it and
 * [createChildrenInSaf] need.
 *
 * The returned mirror path follows any name the provider rewrote. Callers that walk
 * descendants use that path rather than the requested name.
 */
internal fun SafSyncEngine.createOneInSaf(
    localFile: File, parentSafUri: Uri, treeUri: Uri, mirrorRoot: File,
    access: OutboundAccess? = null, mutation: ProviderMutation? = null,
): CreatedDocument? {
    // The claim before the turn, given back inside it, as every other mutation takes
    // them; see [withCurrent]. A claim that was handed in is the caller's to give back.
    if (mutation == null) {
        // A stale drain is let go before it waits for a claim it would never use.
        if (access != null && withCurrent(access) { true } == null) return null
        val owned = claimProviderMutationWithin(parentSafUri, providerClaimWaitMs) ?: return null
        try {
            return if (access == null) createOneInSaf(localFile, parentSafUri, treeUri, mirrorRoot, mutation = owned)
            else withCurrent(access, { releaseProviderMutation(owned) }) {
                createOneInSaf(localFile, parentSafUri, treeUri, mirrorRoot, mutation = owned)
            }
        } finally {
            releaseProviderMutation(owned)
        }
    }
    if (access != null) {
        return withCurrent(access) { createOneInSaf(localFile, parentSafUri, treeUri, mirrorRoot, mutation = mutation) }
    }
    if (!ownsProviderMutation(parentSafUri, mutation)) return null
    var file = localFile
    return try {
        if (isLink(localFile) || (!localFile.isDirectory && !localFile.isFile)) return null
        val parentDocId = DocumentsContract.getDocumentId(parentSafUri)
        val existing = findChildDocument(treeUri, parentDocId, localFile.name)
        val existingDocId = existing?.docId
        if (existing?.isDirectory != null && existing.isDirectory != localFile.isDirectory) {
            // The device holds the other kind under this name. Writing a file into a
            // folder, or making entries inside a file, is refused by the provider and
            // was tried anyway, twice, on every save; the entry stays inside the app
            // and the next open sets it aside next to the device's, see
            // [clearKindConflict]. The caller says so.
            Logger.w(
                tag,
                "Not writing ${localFile.name} back: the device holds a " +
                    "${if (existing.isDirectory) "folder" else "file"} under that name",
            )
            return null
        }
        val docUri = if (existingDocId != null) {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, existingDocId)
        } else {
            // A new empty document must already have recoverable local bytes.
            if (localFile.isFile) openOutboundSource(localFile, mirrorRoot).use { }
            else validateOutboundDirectory(localFile, mirrorRoot)
            val nothingSent = localFile.isFile &&
                (recordUploadIntentImpl(localFile.absolutePath) ?: run {
                    announceLost(localFile)
                    return null
                })
            val mimeType = if (localFile.isDirectory) {
                DocumentsContract.Document.MIME_TYPE_DIR
            } else {
                CREATED_FILE_MIME_TYPE
            }
            var made: Uri? = null
            try {
                made = DocumentsContract.createDocument(
                    context.contentResolver, parentSafUri, mimeType, localFile.name
                )
            } finally {
                // No document was made, so nothing of this file reached the device under its line.
                if (made == null && nothingSent) markUnwritten(localFile.absolutePath)
            }
            made
        } ?: return null

        // The device folder decides what the file is called and the mirror follows;
        // [mirrorNamedAsStored] holds the whole argument, including why the three
        // obvious repairs are each worse. Only for a document this call created: one
        // that was already there was found BY the mirror's name, so the two agree.
        file =
            if (existingDocId == null) {
                mirrorNamedAsStoredImpl(docUri, localFile, storedDocumentName(docUri), mirrorRoot)
            } else {
                localFile
            }

        var delivered = false
        if (file.isFile) {
            // The same question [handleMirrorEvent] asks before queueing a job, asked
            // again here because this path does not come through it. A directory
            // create, or a move whose other half never arrived, walks the tree
            // through [createChildrenInSaf] and reaches every child directly.
            //
            // `existingDocId != null` IS the provider answer for this child, already
            // paid for above, so unlike the event path this costs no extra binder
            // call. A file this sync could not read, whose document still exists on
            // the device, is refused here with the notice for such a document, as the
            // event path refuses it.
            if (writeWouldReplaceUnreadDocument(
                    file.absolutePath, existingDocId != null,
                )
            ) {
                refuseUnreadDocument(file, file.name)
            } else if (existingDocId == null || !keepsDeviceEdit(file, docUri, mirrorRoot, access, preserveUnknown = true)) {
                // A document this call just created is empty, and a stale entry left
                // by a deleted namesake would otherwise set that emptiness aside.
                val written = withCurrent(access) { writeLocalToSafImpl(file, docUri, mirrorRoot, mutation) }
                delivered = written == true
                if (written == null) {
                    announceLost(file)
                }
            }
        }
        // Cache the document ID for future write-back lookups, but only while the
        // cache speaks for this job's tree and knows the parent. A miss is not "the
        // parent is the root" -- initialSync puts the root in under the empty key,
        // so the root is found. The label test is what a drain outliving its own
        // lifecycle needs: the cache then belongs to the next folder, and writing
        // the entry anyway would file a document from the old folder under a
        // plausible name in the new one, so the next write-back for that name would
        // land in the wrong folder entirely. Where two granted folders nest, a
        // document id can be the same in both trees, which is why the label is
        // tested rather than the reverse lookup trusted to miss.
        val parentRelPath = synchronized(docIdCache) {
            if (cacheTree == treeUri) {
                docIdCache.entries.firstOrNull { it.value == parentDocId }?.key
            } else {
                null
            }
        }
        if (parentRelPath != null) {
            // [file] and not [localFile]. Where the provider stored the document under
            // another name the mirror was renamed to match above, and this entry has to
            // name the document as both sides now spell it. Filed under the requested
            // name instead, the MOVED_FROM that rename raises resolves straight to this
            // document and the write-back deletes it.
            val relPath = if (parentRelPath.isEmpty()) file.name
                else "$parentRelPath/${file.name}"
            withCurrentMemory(access) {
                cacheDocId(treeUri, relPath, DocumentsContract.getDocumentId(docUri))
            }
        }
        CreatedDocument(docUri, file, delivered)
    } catch (e: Exception) {
        // The class rather than the message, for the reason [copyDocumentToLocal]
        // spells out: a provider quotes the document URI in its own exception, and
        // the platform provider builds that URI out of the user's own directory path.
        Logger.w(tag, "Failed to create ${localFile.name} in SAF: ${e.javaClass.simpleName}")
        null
    } finally {
        withCurrentMemory(access) {
            for (candidate in setOf(localFile, file)) {
                if (!candidate.exists()) consumeStaleUploadRecordImpl(candidate.absolutePath)
            }
        }
    }
}


internal fun SafSyncEngine.storedDocumentName(docUri: Uri): String? = try {
        context.contentResolver.query(
            docUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )
            if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
        }
    } catch (e: Exception) {
        Logger.d(
            tag,
            "Could not read back a document name: ${e.javaClass.simpleName}",
        )
        null
    }


internal fun SafSyncEngine.mirrorNamedAsStoredImpl(
    docUri: Uri,
    localFile: File,
    stored: String? = storedDocumentName(docUri),
    mirrorRoot: File? = null,
): File {
    val sourceAttrs = runCatching {
        Files.readAttributes(localFile.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    }.getOrNull() ?: return localFile
    if (!sourceAttrs.isRegularFile && !sourceAttrs.isDirectory) return localFile
    if (stored == null || stored == localFile.name) return localFile

    val isDir = sourceAttrs.isDirectory
    val renamed = if (isSafeSegment(stored)) File(localFile.parentFile, stored) else null
    val owner = writeBackSession.get()
    var followed = false
    var insertedIntent = false
    if (renamed != null) {
        val expected = if (isDir) sourceAttrs.fileKey()?.let { localFile.absolutePath to it } else null
        followed = runCatching {
            if (mirrorRoot != null) {
                openOutboundPath(localFile, mirrorRoot).use { dest ->
                    val leafAttrs = inboundAttributes(dest, dest.leaf)
                    val expectedKind = if (isDir) leafAttrs?.isDirectory == true else leafAttrs?.isRegularFile == true
                    val to = dest.sibling(stored)
                    if (!expectedKind || inboundAttributes(dest, to) != null || !parentIsCurrent(dest, localFile, mirrorRoot)) {
                        false
                    } else if (!isDir && recordUploadIntentIfAbsent(renamed.absolutePath).also { insertedIntent = it == true } == null) {
                        false
                    } else {
                        if (owner != null && expected != null && owner.normalizedDirectories.size < MAX_WATCHED_DIRECTORIES) {
                            owner.normalizedDirectories[renamed.absolutePath] = expected
                        }
                        moveToFreeName(dest, localFile, dest.leaf, to)
                    }
                }
            } else {
                val parent = localFile.parentFile
                val parentKey = parent?.let {
                    Files.readAttributes(it.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                }?.takeIf { it.isDirectory && !it.isSymbolicLink }?.fileKey()
                if (parent == null || parentKey == null || Files.exists(renamed.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    false
                } else if (!isDir && recordUploadIntentIfAbsent(renamed.absolutePath).also { insertedIntent = it == true } == null) {
                    false
                } else {
                    if (owner != null && expected != null && owner.normalizedDirectories.size < MAX_WATCHED_DIRECTORIES) {
                        owner.normalizedDirectories[renamed.absolutePath] = expected
                    }
                    when (exclusiveMove(parent, parentKey, localFile.name, stored)) {
                        ExclusiveMove.Outcome.MOVED -> true
                        ExclusiveMove.Outcome.TAKEN -> false
                        ExclusiveMove.Outcome.UNKNOWN ->
                            !Files.exists(localFile.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                                Files.exists(renamed.toPath(), LinkOption.NOFOLLOW_LINKS)
                        ExclusiveMove.Outcome.UNAVAILABLE -> {
                            val currentKey = Files.readAttributes(
                                parent.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS,
                            ).takeIf { it.isDirectory && !it.isSymbolicLink }?.fileKey()
                            currentKey == parentKey &&
                                !Files.exists(renamed.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                                localFile.renameTo(renamed)
                        }
                    }
                }
            }
        }.getOrDefault(false)
        if (!followed && owner != null && expected != null) {
            owner.normalizedDirectories.remove(renamed.absolutePath, expected)
        }
    }
    if (!followed) {
        if (insertedIntent && renamed != null) consumeStaleUploadRecordImpl(renamed.absolutePath)
        Logger.w(
            tag,
            "The device folder stored ${localFile.name} as $stored and the mirror could " +
                "not follow; the editor will show both the next time the folder is opened",
        )
        return localFile
    }
    if (isDir) {
        renameUploadsUnder(localFile, renamed!!)
        renameDeviceMemoryUnder(localFile, renamed)
    } else consumeStaleUploadRecordImpl(localFile.absolutePath)
    Logger.i(
        tag,
        "The device folder stored ${localFile.name} as $stored; the mirror copy now " +
            "carries that name too",
    )
    return renamed!!
}


/**
 * What the device reports for [docUri] now, as (COLUMN_LAST_MODIFIED, COLUMN_SIZE), or
 * null when the provider does not answer. A missing column, and a negative size, read as
 * 0, the rules [walkTree] applies, so both sides of a [deviceSeen] comparison are read
 * alike and no answer carries [UNANSWERED_SIZE].
 */
internal fun SafSyncEngine.deviceStamp(docUri: Uri): Pair<Long, Long>? = try {
    context.contentResolver.query(
        docUri,
        arrayOf(
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        ),
        null, null, null,
    )?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val modifiedIndex =
            cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
        val modified = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
            cursor.getLong(modifiedIndex)
        } else {
            0L
        }
        val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex).coerceAtLeast(0) else 0L
        modified to size
    }
} catch (e: Exception) {
    null
}


/** The current kind of this identity, which a provider may reuse after deletion. */
internal fun SafSyncEngine.documentIsDirectory(docUri: Uri): Boolean? = try {
    context.contentResolver.query(
        docUri, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null,
    )?.use { cursor ->
        val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
        if (index < 0 || !cursor.moveToFirst()) return@use null
        val mime = cursor.getString(index)
        if (mime.isNullOrEmpty()) null else mime == DocumentsContract.Document.MIME_TYPE_DIR
    }
} catch (_: Exception) {
    null
}


/**
 * The SHA-256 of what the device holds for [docUri], read without keeping a copy, or
 * null when the provider will not give it or holds more than [limit] bytes, where the
 * read stops: more bytes than a digest covers cannot hash to it.
 */
internal fun SafSyncEngine.deviceSha256(docUri: Uri, limit: Long): ByteArray? = try {
    withStableDocumentRead(docUri) {
        context.contentResolver.openInputStream(docUri)?.use { sha256(it, limit)?.first }
    }
} catch (e: Exception) {
    null
}


/**
 * Creates everything under [localDir] on the device, beneath [dirSafUri].
 *
 * Iterative rather than recursive through [createInSaf], and that is deliberate:
 * [uploadPlan] already returns parents before children, so each entry's
 * parent document exists by the time it is reached, and the URI of every
 * directory created along the way is remembered here. Recursing instead would
 * re-query the provider for a parent it had just made.
 *
 * A child whose parent is missing from [created] is skipped rather than guessed
 * at. That happens when the parent's own creation failed, and inventing a place
 * to put the child would file it somewhere the user did not put it.
 *
 * A folder a rename queued behind this job moves to its path, [movedHere], is left to
 * that rename with everything under it, as [notVouchedUnder] leaves it: `mkdir lib &&
 * mv util lib/` queues the create of `lib` ahead of the move, and the create used to
 * send `lib/util` from the copy, so the move met a folder of its name there and the
 * device kept `util` with its `.git` beside a copy of it without one. A rename that
 * then fails makes the folder from the copy itself, or says it did not.
 */
internal fun SafSyncEngine.createChildrenInSaf(
    localDir: File, dirSafUri: Uri, treeUri: Uri, mirrorRoot: File, access: OutboundAccess? = null,
    delivered: MutableSet<String>? = null,
    mutation: ProviderMutation? = null,
    movedHere: Collection<String> = emptyList(),
) {
    val plan = uploadPlan(localDir, MAX_UPLOAD_ENTRIES)
    val entries = plan.entries
    if (entries.isEmpty()) return

    val created = mutableMapOf(localDir.absolutePath to dirSafUri)
    val movedDirectories = mutableMapOf<String, String>()
    var made = 0
    for (original in entries) {
        val path = original.absolutePath
        if (delivered?.contains(path) == true || movedHere.any { path == it || path.startsWith(it + File.separator) } ||
            keptFromCreates.isNotEmpty() && original.isFile &&
            keptFromCreatesKey(mirrorRoot, identityLine(original.toRelativeString(mirrorRoot), original)) in keptFromCreates
        ) {
            made++
            continue
        }
        val entry = movedMirrorFile(original, movedDirectories)
        // Split rather than chained: `parentFile` is a platform type, so the
        // chained form indexed the map with a String? and the compiler warned
        // about it. An entry with no parent cannot be placed anywhere.
        val parentPath = original.parentFile?.absolutePath ?: continue
        val parentUri = created[parentPath] ?: continue
        createOneInSaf(entry, parentUri, treeUri, mirrorRoot, access, mutation)?.let { result ->
            if (result.localFile.isDirectory) {
                created[original.absolutePath] = result.uri
                movedDirectories[original.absolutePath] = result.localFile.absolutePath
                made++
            } else if (result.delivered) {
                delivered?.add(original.absolutePath)
                made++
            }
        }
    }
    // The walk's own answer, not `entries.size >= MAX_UPLOAD_ENTRIES`. That test
    // reported a directory holding exactly the cap as incompletely copied when every
    // entry of it had just been created, because the walk stops at the cap and so
    // can never return more than it.
    val capped = plan.truncated
    if (capped) {
        Logger.w(tag, "Stopped at $MAX_UPLOAD_ENTRIES entries under ${localDir.name}; " +
            "the rest were not copied to the device")
    }
    Logger.i(tag, "Created $made of ${entries.size} entries under ${localDir.name}")

    // One notice for the directory, not one per entry. `made` already counts both
    // ways an entry can be lost: a creation the provider refused, and a child
    // skipped because its own parent was never created. Reading the shortfall off
    // that count is what keeps a new silent branch from appearing here later, the
    // way one did when this reported only to the log.
    //
    // The comment above the cap used to say a log line was the only place that
    // could say so. That was true when it was written and stopped being true when
    // the notice channel arrived, which is the shape a reason comment rots in.
    val lost = entries.size - made
    if (lost > 0 || capped) onUploadIncomplete(localDir, lost, capped)
}


/**
 * Looks up a direct child of [parentDocId] by display name, from the provider.
 *
 * Deliberately not through [docIdCache]: both callers use the answer to choose
 * between writing into a document and making a new one, and the cache can still
 * name a document deleted since it was filled.
 */
internal fun SafSyncEngine.findChildDocId(treeUri: Uri, parentDocId: String, name: String): String? =
    try {
        findChildDocument(treeUri, parentDocId, name)?.docId
    } catch (_: Exception) {
        null
    }


/**
 * [findChildDocId] with what kind of document the provider says it is.
 *
 * A name is not an identity: a folder and a file can take the same name in turn, and a
 * caller that writes into what it found by name has to know it found the kind it meant.
 * [ChildDocument.isDirectory] is null where the provider reported no usable type, and
 * a caller treats that as unknown rather than as a file.
 */
internal fun SafSyncEngine.findChildDocument(treeUri: Uri, parentDocId: String, name: String): ChildDocument? {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
    val listing = context.contentResolver.query(
        childrenUri,
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        ),
        null, null, null,
    ) ?: throw IOException("The device directory listing is unavailable")
    return listing.use { cursor ->
        if (cursor.extras?.let {
                it.getBoolean(DocumentsContract.EXTRA_LOADING) ||
                    it.containsKey(DocumentsContract.EXTRA_ERROR)
            } == true
        ) throw IOException("The device directory listing is incomplete")
        val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
        var found: ChildDocument? = null
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) != name) continue
            if (found != null) throw IOException("The device directory has ambiguous names")
            val mime = cursor.getString(mimeIndex)
            found = ChildDocument(
                cursor.getString(idIndex),
                mime?.takeIf { '/' in it }
                    ?.let { it == DocumentsContract.Document.MIME_TYPE_DIR },
            )
        }
        found
    }
}


internal fun movedMirrorFile(file: File, moved: Map<String, String>): File {
    val parent = moved.keys.filter { file.absolutePath.startsWith(it + File.separator) }
        .maxByOrNull { it.length } ?: return file
    return File(moved.getValue(parent) + file.absolutePath.removePrefix(parent))
}


/**
 * Gives an existing device document a new display name, in place.
 *
 * The whole point is what it does *not* touch: the provider moves the document itself,
 * so everything beneath it travels along, including the `.git` and `node_modules` the
 * mirror never held and the files past [MAX_UPLOAD_ENTRIES] it could not have re-sent.
 * Nothing else here can move a subtree that the mirror does not fully contain.
 *
 * @return the renamed document's URI, or null if the provider refused it.
 */
internal fun SafSyncEngine.renameInSaf(safDocUri: Uri, newName: String): Uri? = try {
    DocumentsContract.renameDocument(context.contentResolver, safDocUri, newName)
} catch (e: Exception) {
    Logger.w(tag, "Could not rename a document to $newName: ${e.javaClass.simpleName}")
    null
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
internal fun SafSyncEngine.moveInSaf(safDocUri: Uri, sourceParentUri: Uri, targetParentUri: Uri): Uri? = try {
    DocumentsContract.moveDocument(
        context.contentResolver, safDocUri, sourceParentUri, targetParentUri
    )
} catch (e: Exception) {
    Logger.w(tag, "Could not move a document between directories: ${e.javaClass.simpleName}")
    null
}


/**
 * Deletes a document from the SAF tree.
 */
/**
 * Removes the document, and answers whether the device folder agreed.
 *
 * The Boolean is the point. `deleteDocument` reports a refusal by returning
 * false as readily as by throwing, and both used to end here: the mirror
 * entry is unlinked either way, so the file left the editor while the device
 * kept it, and the next open copied it back. What a user sees is a file they
 * deleted returning, with nothing in between saying why.
 */
internal fun SafSyncEngine.deleteFromSafImpl(safDocUri: Uri): Boolean = try {
    DocumentsContract.deleteDocument(context.contentResolver, safDocUri)
} catch (e: Exception) {
    Logger.w(tag, "Failed to delete from SAF: ${e.javaClass.simpleName}")
    false
}

// -- Internal: Write-back Processing --


/**
 * Whether the provider itself answers for [relativePath], asked segment by
 * segment rather than through [docIdCache].
 *
 * The cache is the right answer for the paths jobs write into, but it is a
 * record of what was true when it was filled, and nothing maintains it
 * against deletions the provider performed. A decision that turns on the
 * device *not* holding a name has to ask the device.
 */
/**
 * Whether the device still holds anything at [relativePath]: true, false, or null
 * when the provider could not be asked. Type-agnostic, because it walks display
 * names, which is what the delete guard needs: it asks the same question of a
 * directory and of the document inside one.
 *
 * Loading and error listings, null cursors and failed queries answer unknown.
 * Both write and delete guards treat only a complete answer of "gone" as permission
 * to replace a document this sync never read.
 */
@JvmName("providerHolds")
internal fun SafSyncEngine.providerHoldsImpl(treeUri: Uri, relativePath: String): Boolean? {
    var currentDocId = DocumentsContract.getTreeDocumentId(treeUri)
    for (segment in relativePath.split("/")) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, currentDocId)
        val next = try {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null, null, null
            )?.use { cursor ->
                if (cursor.extras?.let {
                        it.getBoolean(DocumentsContract.EXTRA_LOADING) ||
                            it.containsKey(DocumentsContract.EXTRA_ERROR)
                    } == true
                ) return null
                val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                var found: String? = null
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == segment) {
                        found = cursor.getString(idIndex)
                        break
                    }
                }
                found ?: return false
            } ?: return null
        } catch (e: Exception) {
            Logger.d(tag, "Lookup of $segment under $currentDocId failed: ${e.message}")
            return null
        }
        currentDocId = next
    }
    return true
}


internal fun SafSyncEngine.providerHoldsDocumentImpl(treeUri: Uri, relativePath: String): Boolean =
    providerHoldsImpl(treeUri, relativePath) != false


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
internal fun SafSyncEngine.forgetCachedSubtree(treeUri: Uri, relativePath: String) {
    val prefix = "$relativePath/"
    synchronized(docIdCache) {
        if (cacheTree == treeUri) docIdCache.keys.removeAll { it == relativePath || it.startsWith(prefix) }
    }
}


/**
 * Resolves a document URI within a SAF tree given a relative path.
 * Uses [docIdCache] for O(1) lookup when available, falling back to tree
 * traversal on a cache miss.
 */
internal fun SafSyncEngine.resolveDocumentUriImpl(treeUri: Uri, relativePath: String, access: OutboundAccess? = null): Uri? {
    if (relativePath.isEmpty()) {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
    }

    // Fast path: use cached docId if available, and only while the cache speaks
    // for this tree.
    val cached = cachedDocId(treeUri, relativePath)
    if (cached != null) {
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, cached)
    }

    // Slow path: walk the tree segment by segment (for newly created files)
    var currentDocId = DocumentsContract.getTreeDocumentId(treeUri)
    for (segment in relativePath.split("/")) {
        currentDocId = findChildDocId(treeUri, currentDocId, segment) ?: return null
    }

    // Cache for next time, unless the cache moved on to another tree during the
    // walk above; then this answer belongs to a folder it no longer speaks for.
    withCurrentMemory(access) { cacheDocId(treeUri, relativePath, currentDocId) }
    return DocumentsContract.buildDocumentUriUsingTree(treeUri, currentDocId)
}
