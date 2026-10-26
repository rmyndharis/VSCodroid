package com.vscodroid.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.annotation.StringRes
import com.vscodroid.R
import com.vscodroid.util.Logger
import com.vscodroid.util.StorageManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import androidx.core.content.edit

/**
 * Manages Storage Access Framework (SAF) interactions for VSCodroid.
 *
 * Responsibilities:
 * - Launching/handling folder picker results
 * - Persisting URI permissions across app restarts
 * - Tracking recently opened SAF folders
 * - Coordinating initial sync + ongoing file watching via [SafSyncEngine]
 *
 * SAF provides access to user-selected folders via content:// URIs. Since
 * VS Code Server (Node.js) requires real filesystem paths, we maintain a
 * local "mirror" directory that is kept in sync with the SAF source.
 */
class SafStorageManager(context: Context) {

    /**
     * The application context, whatever the caller handed over.
     *
     * Both production callers construct this with an Activity, and one of them
     * ([com.vscodroid.SplashActivity]) then starts [reclaimRevokedMirrors], whose
     * detached thread captures this object and through it whatever Context it
     * holds. That thread outlives the Activity by construction (the file says so
     * where the thread is started), and its duration is the recursive delete of a
     * project-sized mirror, so an Activity kept here stays reachable, with its
     * Window and its ContextImpl, through exactly the minutes when `MainActivity`,
     * the WebView renderer and the Node server are all starting.
     *
     * Nothing here needs an Activity: what is read is `contentResolver`, `filesDir`
     * (through [com.vscodroid.util.Environment]) and the preferences file, all
     * identical on the application context. This is the same reasoning
     * `AndroidBridge` gives for building its `ToolchainManager` on
     * `applicationContext`.
     *
     * ⚠️ The two initialisers below say `this.context` deliberately. A constructor
     * parameter shadows the property of the same name inside an initialiser, so a
     * bare `context` there is the Activity again, and handing that to
     * [SafSyncEngine] would keep the whole retention with the unwrapping still in
     * place and reading as though it worked.
     */
    private val context: Context = context.applicationContext ?: context

    private val tag = "SafStorageManager"
    private val prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val syncEngine = SafSyncEngine(this.context)

    /**
     * Told when a write-back has given up on a file, once per burst.
     *
     * Forwarded from the engine rather than set on it directly, so the caller does not
     * have to know the engine exists. [MainActivity] is the one caller, because saying
     * anything needs a screen.
     *
     * Throttled here rather than at the call site: a provider that has started refusing
     * refuses everything, and the editor saves on a timer, so the unthrottled version is
     * a wall of the same notice. Globally rather than per file, for the same reason. The
     * user's problem is the folder.
     *
     * The reading is the monotonic clock, never wall time, and that is the same reason
     * `AuthTabWindow` gives one file over. The throttle is a subtraction against a stamp
     * held for the life of this object, so a wall clock corrected backwards by more than
     * the interval (NTP after a drifted RTC, or the user setting the date) leaves every
     * later difference below it, and a folder that is still refusing every save
     * says nothing at all until the clock catches up. Silence is the failure this notice
     * exists to prevent.
     */
    fun onWriteBackFailed(announce: (File) -> Unit) {
        syncEngine.onWriteBackFailed = { file ->
            if (claimAnnouncement(SystemClock.elapsedRealtime(), lastFailureAnnouncedAt.get())) {
                announce(file)
            }
        }
    }

    /**
     * Whether the caller that read [last] off the throttle is the one that gets to speak.
     *
     * `compareAndSet`, not read-then-write. Two threads genuinely arrive here: the
     * `saf-writeback` daemon draining the queue, and `Dispatchers.IO` running the
     * write-backs `initialSync` issues itself. `@Volatile` gave visibility but not
     * atomicity, so both could read the same stale [last], both find the interval
     * elapsed, and both announce for one burst, which is the wall of toasts the
     * throttle exists to prevent.
     *
     * The loser does not retry, deliberately. Losing means another thread has just
     * announced this same burst, which is the answer the user needed.
     *
     * [last] is a parameter rather than a read taken here, and that is what makes the
     * claim assertable: a caller can be handed a reading that another caller has already
     * consumed, which is the losing thread's whole situation, without any threads being
     * involved. Racing two of them is not an instrument for this. Measured on this JDK
     * with both shapes behind a `CyclicBarrier`, the read-then-write version announced
     * twice in 0 of 50,000 two-thread trials, 0 of 20,000 eight-thread trials and 0 of
     * 2,000 sixteen-thread ones: the window is one read, a subtraction and one write,
     * and the throttle confines it to the first instant of each interval, so a race test
     * would have reported the shape this replaced as green.
     */
    internal fun claimAnnouncement(
        now: Long,
        last: Long,
        stamp: AtomicLong = lastFailureAnnouncedAt,
    ): Boolean = shouldAnnounce(now, last) && stamp.compareAndSet(last, now)

    /**
     * Told when something deleted in the editor was kept on the device: the first flag
     * says whether it was a directory holding documents that never reached the editor, or
     * one such document itself, and the second whether it was kept instead for a version
     * of a file the editor had that may have changed on the device since; see
     * [SafSyncEngine.onKeptOnDevice].
     *
     * Forwarded for the reason the others are. Throttled like [onWriteBackFailed] and
     * unlike the two below, because one deletion is not one directory: `rm -r` reports
     * the deepest directory first, and every ancestor of a kept document is kept in
     * turn, so deleting `docs/a/b` with an unread archive at the bottom is three
     * notices back to back. On a stamp of its own rather than [lastFailureAnnouncedAt],
     * so the burst cannot swallow a write-back failure landing inside the same
     * interval, nor be swallowed by one.
     *
     * Both cases share that stamp, which is a deliberate simplification with a known
     * ceiling: an `rm -r` whose ancestors are all kept can swallow a kept file landing
     * inside the same interval. A stamp apiece would close it, at the price of a fifth
     * seam for a sentence one word different from this one.
     */
    fun onKeptOnDevice(announce: (File, Boolean, Boolean) -> Unit) {
        syncEngine.onKeptOnDevice = { file, isDirectory, changed ->
            val last = lastKeptAnnouncedAt.get()
            if (claimAnnouncement(SystemClock.elapsedRealtime(), last, lastKeptAnnouncedAt)) {
                announce(file, isDirectory, changed)
            }
        }
    }

    /**
     * Told when the device folder refused a deletion the editor made.
     *
     * Shares [lastKeptAnnouncedAt] with [onKeptOnDevice] rather than taking a
     * stamp of its own, for the reason the comment above gives for the two cases
     * already sharing it: both sentences end "it is still in the device folder",
     * and an `rm -r` that a provider refuses raises one per entry. One notice for
     * the burst is the useful count; the log carries every name.
     */
    fun onDeleteRefused(announce: (File) -> Unit) {
        syncEngine.onDeleteRefused = { file ->
            val last = lastKeptAnnouncedAt.get()
            if (claimAnnouncement(SystemClock.elapsedRealtime(), last, lastKeptAnnouncedAt)) {
                announce(file)
            }
        }
    }

    /**
     * Told when a folder opened without every document reaching the mirror.
     *
     * Forwarded rather than set on the engine directly, for the reason
     * [onWriteBackFailed] is, and unthrottled for the reason that one is throttled: this
     * fires at most once per folder open and the count it carries is the whole burst.
     */
    fun onDocumentsNotCopied(announce: (Int, Boolean) -> Unit) {
        syncEngine.onDocumentsNotCopied = announce
    }

    /**
     * Told when a directory copied out to the device arrived incomplete.
     *
     * Forwarded rather than set on the engine directly, for the reason the two above
     * are. Unthrottled, like [onDocumentsNotCopied] and unlike [onWriteBackFailed]:
     * this fires once per directory copy and the count it carries is the whole burst,
     * so there is nothing for a throttle to collapse.
     */
    fun onUploadIncomplete(announce: (File, Int, Boolean) -> Unit) {
        syncEngine.onUploadIncomplete = announce
    }

    private val lastFailureAnnouncedAt = AtomicLong(NEVER_ANNOUNCED)
    private val lastKeptAnnouncedAt = AtomicLong(NEVER_ANNOUNCED)

    /** Claims paths before workspace publication or a remote filesystem request. */
    fun claimWorkspacePaths(paths: List<String>): Boolean {
        val root = com.vscodroid.util.Environment.getSafMirrorsDir(context)
        val mirrors = paths.flatMap { path ->
            val file = File(path)
            val spellings = listOf(file.toPath().normalize().toString(), runCatching { file.canonicalPath }.getOrNull())
            spellings.mapNotNull { mirrorNameFor(it, root)?.takeIf(::isMirrorDirectoryName) }
        }.map { File(root, it).absolutePath }.toSet()
        return synchronized(mirrorUsageLock) {
            if (mirrors.any { it in mirrorsBeingRemoved || !File(it).isDirectory }) return@synchronized false
            mirrorsUsedThisProcess.addAll(mirrors)
            true
        }
    }

    /** An attempted open may create its mirror after the permission is taken. */
    internal fun claimMirrorOpen(mirror: File): Boolean = synchronized(mirrorUsageLock) {
        if (mirror.absolutePath in mirrorsBeingRemoved) return@synchronized false
        mirrorsUsedThisProcess.add(mirror.absolutePath)
        true
    }

    internal fun usedMirrorNames(): Set<String> {
        val root = com.vscodroid.util.Environment.getSafMirrorsDir(context) + File.separator
        return synchronized(mirrorUsageLock) {
            mirrorsUsedThisProcess.filter { it.startsWith(root) }.map { it.removePrefix(root) }.toSet()
        }
    }

    // -- Permission Management --

    /**
     * Takes a persistable URI permission for the given tree URI.
     *
     * This must be called with the URI returned from [ActivityResultContracts.OpenDocumentTree].
     * Persisted permissions survive across app restarts and device reboots until
     * the user revokes them in system settings or this app releases them, which it
     * does for a folder pushed off the recent list and for one whose local copy the
     * user removes.
     */
    fun persistPermission(uri: Uri) = synchronized(grantLifecycleLock) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            // Between the grant and the row, and that order is what [grantsTaken] is
            // read against: a reader whose snapshot of the grants was taken before this
            // line must not judge the row the next one writes.
            grantsTaken.incrementAndGet()
            addToRecentFolders(uri)
            Logger.i(tag, "Persisted permission for ${getMirrorDir(uri).name}")
        } catch (e: SecurityException) {
            // Neither the URI nor the throwable, and it takes both to be redaction at
            // all. A SAF tree URI spells the user's own directory
            // (`.../tree/primary%3ADocuments%2F<folder>`), and
            // takePersistableUriPermission's SecurityException quotes that URI in its
            // own message, so dropping the interpolation while still passing the
            // exception along would put the same string in logcat by another route.
            // Logger.i, .w and .e are not gated on a debuggable build, so every one of
            // them ships. The mirror name is the six-byte digest of that same URI: it is
            // stable, it is not reversible, and it lines this up with every other line
            // the app writes about the folder.
            Logger.e(
                tag,
                "Could not persist the permission for ${getMirrorDir(uri).name}: " +
                    e.javaClass.simpleName,
            )
        }
    }

    /**
     * Checks if we still hold a valid persisted permission for the given URI.
     *
     * Both halves, because a session opened on this answer needs both. [persistPermission]
     * takes read and write together, but this asked only about read while every
     * local-to-device call the session then makes needs write: `openOutputStream(uri,
     * "wt")`, `createDocument`, `renameDocument`, `moveDocument`, `deleteDocument`. On a
     * read-only grant the folder opened, the sync completed and the watcher started, and
     * the user's saves then failed one at a time behind a notice throttled to one per ten
     * seconds for the whole folder. Refusing up front is the same answer the revoked case
     * already gets, arriving before any of that.
     *
     * ⚠️ [persistedReadUris], which drives the recent-list prune, deliberately stays on
     * read alone: a folder this app cannot write to still belongs in Open Recent, and
     * pruning it there would take its grant with it.
     */
    fun hasPersistedPermission(uri: Uri): Boolean {
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
    }

    // -- Recent Folders --

    /**
     * The device folder whose mirror the workbench has opened, if it opened one.
     *
     * VS Code switches folders by navigating its own WebView, so a folder reached
     * through Open Recent, the Get Started list or Open Folder never passes
     * through the picker and never starts a watcher. Kotlin sees only the
     * finished page load, and this is what turns that URL back into the grant it
     * belongs to.
     */
    fun folderForOpenedPath(opened: String): SafFolderInfo? =
        folderForOpenedPath(getPersistedFolders(), opened)

    /**
     * Returns the list of recently opened SAF folders with persisted permissions.
     * Folders whose permissions have been externally revoked are pruned from the list.
     *
     * Pruning the list is all this does. Deleting the mirror of a pruned folder used to
     * happen here too, which put a recursive delete of the user's files inside a method
     * the workbench calls whenever it wants the recent list, and made a permission that
     * read as absent for a moment enough to take the mirror of the folder currently open
     * in the editor out from under it. That reclamation lives in [reclaimRevokedMirrors]
     * alone now.
     */
    fun getPersistedFolders(): List<SafFolderInfo> {
        // Read before the lock, and as one call rather than one per entry. The prune
        // below used to ask the system server for each folder in turn, so a listing held
        // the monitor across up to [MAX_RECENT] binder round trips while the bridge's
        // disk-work thread, a sync on Dispatchers.IO and the reclaim thread waited on
        // it. One reading also judges every entry against one answer, which is what the
        // per-entry version could not promise.
        //
        // What this does not buy is a monitor free of binder calls, which is how the
        // hoist has already been read once. Three of the four read-modify-writes the
        // lock exists for call this from inside it ([releaseGrantFor],
        // [addToRecentFolders], [updateLastOpened]; the fourth is the prune below), and
        // each of those nested calls makes its own [persistedReadUris] round trip with
        // the monitor already held. What the hoist bounds is the cost of that: one round
        // trip rather than one per entry.
        // Who pays it is a background thread in every case, so it is a wait and not a
        // freeze: MainActivity hops folderForOpenedPath, persistPermission and
        // syncToLocal onto Dispatchers.IO (DeviceFolderOpenThreadTest pins that), the
        // bridge answers listings on its disk-work executor and on the JavaBridge
        // thread, and [reclaimRevokedMirrors] runs on a thread of its own.
        val stamp = grantsTaken.get()
        val granted = persistedReadUris()
        return synchronized(recentFoldersLock) {
            // What holding the reading inside the lock used to buy, without holding it
            // there. The prune below is a read-modify-write that DELETES rows, and a
            // reading taken before the lock can be older than the list read inside it: a
            // grant taken in between belongs to a row the picker saves before this thread
            // gets the monitor, and judging that row against the older reading prunes the
            // folder the user just picked and persists the list without it, with the
            // grant still held. Nothing here can tell which row that would be, so against
            // such a reading no row is judged at all: the list is returned as it stands
            // and left alone, and the next read, whose reading names the new grant,
            // prunes then. Keeping a row too long is the harmless direction, because the
            // pass that deletes mirrors is [reclaimRevokedMirrors] and it never consults
            // this list.
            readAndPruneRecentFolders(granted.takeIf { grantsTaken.get() == stamp })
        }
    }

    /** Every tree this app still holds a persisted read grant for, in one binder call. */
    private fun persistedReadUris(): Set<Uri> =
        context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri }
            .toSet()

    /**
     * The body of [getPersistedFolders]. Caller must hold [recentFoldersLock]: the
     * prune below is itself a read-modify-write of the list.
     *
     * [granted] is every tree still granted, as of a reading the caller took. Null means
     * the caller cannot vouch that its reading is not older than this list, and then
     * nothing is pruned and nothing is written: see [getPersistedFolders].
     */
    private fun readAndPruneRecentFolders(granted: Set<Uri>?): List<SafFolderInfo> {
        val json = prefs.getString(KEY_RECENT_FOLDERS, "[]") ?: "[]"
        val array = JSONArray(json)
        val result = mutableListOf<SafFolderInfo>()
        val toRemove = mutableListOf<Int>()

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val uri = Uri.parse(obj.getString("uri"))

            // Prune folders whose permissions have been revoked externally
            if (granted != null && uri !in granted) {
                toRemove.add(i)
                continue
            }

            // A name the provider gave, or none: a stored name that is the fallback was
            // written by a build that did not tell the two apart, and is no more an answer
            // than a missing one. Either way the label is never empty, which the list
            // and the notices print as it stands.
            val fallback = fallbackDisplayName(uri)
            val answered = obj.optString("name", "").takeIf { it.isNotBlank() && it != fallback }
            result.add(
                SafFolderInfo(
                    uri = uri,
                    displayName = answered ?: fallback,
                    lastOpened = obj.optLong("lastOpened", 0),
                    mirrorPath = getMirrorDir(uri).absolutePath,
                    named = answered != null,
                )
            )
        }

        // Persist pruned list if any entries were removed
        if (toRemove.isNotEmpty()) {
            saveRecentFolders(result)
            Logger.i(tag, "Pruned ${toRemove.size} revoked folder(s) from recent list")
        }

        return result.sortedByDescending { it.lastOpened }
    }

    /**
     * Finishes earlier committed removals and clears orphan sync metadata.
     *
     * Ordinary copies remain available even after their persisted permission is
     * revoked. A server or terminal can still write into them, so launch timing and
     * a matching snapshot do not authorize an automatic directory removal.
     *
     * Runs off the UI thread because a committed discarded tree may be large.
     * Interrupting deletion leaves the next launch to finish the same discarded
     * paths, while a folder granted again receives a distinct ordinary directory.
     */
    fun reclaimRevokedMirrors() {
        thread(name = "saf-reclaim", isDaemon = true) {
            try {
                reclaimRevokedMirrorsSync()
            } catch (e: Exception) {
                // Nothing depends on this having run: the next launch tries again, and
                // until then the cost is disk that is already spent.
                Logger.w(tag, "Mirror reclamation pass failed: ${e.message}")
            }
        }
    }

    /**
     * Finishes committed removals and removes records whose ordinary copy is gone.
     *
     * Ordinary mirrors can receive direct writes from terminals and surviving server
     * processes without taking this process's locks. A matching sync snapshot cannot
     * prove that a directory stays unchanged until a later rename, so those copies and
     * their records remain available for explicit removal from the storage screen.
     */
    internal fun reclaimRevokedMirrorsSync(
        listEntries: (File) -> Array<File>? = File::listFiles,
    ): Int {
        fun liveMirrorNames(): Set<String> =
            context.contentResolver.persistedUriPermissions
                .map { getMirrorDir(it.uri).name }
                .toSet()

        val root = File(com.vscodroid.util.Environment.getSafMirrorsDir(context))
        var removed = 0
        listEntries(root)?.forEach { entry ->
            val name = entry.name
            val alreadySetAside = name.startsWith(DISCARD_PREFIX) &&
                MIRROR_ENTRY.matches(name.removePrefix(DISCARD_PREFIX))
            if (alreadySetAside) {
                if (finishOff(entry, File(root, name.removePrefix(DISCARD_PREFIX)))) removed++
                return@forEach
            }

            val partial = MIRROR_RECORD_PARTIAL.matches(name)
            val record = MIRROR_ENTRY.matches(name) &&
                name.endsWith(SafSyncEngine.SYNCED_RECORD_SUFFIX)
            if (!partial && !record) return@forEach
            // A directory with a record's name is not a record this app writes.
            if (entry.isDirectory && !Files.isSymbolicLink(entry.toPath())) return@forEach
            val hash = name.substringBefore('.')
            val discarded = synchronized(grantLifecycleLock) {
                // Regrant and sync use this same lock. A dangling symbolic link also
                // keeps its record: do not infer ownership from its missing target.
                if (Files.exists(File(root, hash).toPath(), LinkOption.NOFOLLOW_LINKS) ||
                    hash in liveMirrorNames()
                ) {
                    null
                } else if (partial) {
                    if (entry.delete()) removed++
                    null
                } else {
                    setAside(root, name)
                }
            }
            if (discarded != null && finishOff(discarded, entry)) removed++
        }
        forgetNamesOfGoneCopies(root, ::liveMirrorNames)
        if (removed > 0) Logger.i(tag, "Removed $removed discarded mirror entr(ies) or orphan records")
        return removed
    }

    /**
     * Removes the named paths ([namedPathFor]) and the mark of every copy that has gone.
     *
     * They hold nothing, but each spells a folder's name, so they are not left behind
     * once the folder's copy is deleted. A copy is gone when its directory is and no
     * grant is held for it: a folder granted again keeps them, and its next open makes
     * any that are missing. Only what [namedPathFor] and [noteOpened] make is removed,
     * links and the mark: a directory holding anything else is kept. Not counted as
     * reclaimed, since no disk to speak of comes back.
     */
    private fun forgetNamesOfGoneCopies(root: File, liveMirrorNames: () -> Set<String>) {
        File(root, NAMED_DIR).listFiles()?.forEach { entry ->
            val hash = entry.name.removeSuffix(SHOWN_SUFFIX)
            synchronized(grantLifecycleLock) {
                if (!isMirrorDirectoryName(hash) || File(root, hash).exists() || hash in liveMirrorNames()) {
                    return@forEach
                }
                // Never follow a link put in place of the named directory.
                if (entry.name == hash && !Files.isSymbolicLink(entry.toPath())) {
                    entry.listFiles()?.filter { Files.isSymbolicLink(it.toPath()) }?.forEach { it.delete() }
                }
                entry.delete()
            }
        }
    }

    /**
     * Renames one entry out of the way, and answers with where it went.
     *
     * The rename is the commit point of every removal in this file, and the reason is
     * in [reclaimRevokedMirrorsSync]: a delete in place rests on nothing else touching
     * the directory while the walk is inside it, and a mirror the size of a project
     * takes long enough for the user to re-grant the same folder and re-sync it there.
     * The walk's remaining deletes then land on the new copy under a running watcher
     * and go out to the device as deletions of the user's real documents. A rename is
     * atomic and instant, so the re-granted folder gets a fresh directory this removal
     * cannot reach.
     *
     * It is also what makes an interrupted removal safe to abandon. Everything after
     * the rename is idempotent and is finished off unconditionally by the next launch
     * pass, so a return added between here and the delete costs disk until the next
     * launch rather than correctness. That is the property to preserve when editing
     * either caller.
     *
     * @return the set-aside entry, or null when there was nothing to move or the
     *   rename failed. An entry already carrying [DISCARD_PREFIX] is returned as it
     *   stands: an earlier pass reached this same point and its verdict still holds.
     */
    private fun renameAside(root: File, name: String): File? {
        if (name.startsWith(DISCARD_PREFIX)) return File(root, name).takeIf { it.exists() }
        val entry = File(root, name)
        if (!entry.exists()) return null
        val target = File(root, DISCARD_PREFIX + name)
        if (!entry.renameTo(target)) {
            Logger.w(tag, "Could not set $name aside; leaving it in place")
            return null
        }
        return target
    }

    private fun setAside(root: File, name: String): File? {
        val entry = File(root, name)
        val target = renameAside(root, name) ?: return null
        // The records move with the directory, and this is the only moment they can.
        // They are keyed by absolute path, and the name is a hash of the tree URI
        // rather than of the session, so from here on the path this rename frees is
        // the path the same folder gets again the moment the user re-grants it. Left
        // behind, the departing mirror's records and the replacement's are the same
        // strings and nothing downstream can tell them apart.
        if (entry != target) syncEngine.renameUploadsUnder(entry, target)
        return target
    }

    /**
     * Deletes an entry [setAside] has already moved, and retires the upload records
     * that named it.
     *
     * The records are retired under the `discarded-` path, which is the path they are
     * filed under: [setAside] moves them when it moves the directory. Clearing under
     * the name the mirror had *before* the rename is what this used to do, and it is
     * wrong in the one case that matters. A mirror's directory name is a hash of the
     * tree URI, so the path a removal frees is the path the same folder returns to when
     * the user re-grants it, and a sweep finishing after that re-grant then retires the
     * live mirror's records instead of the dead one's. That loses the very distrust
     * that stops the next sync copying a truncated device document over the mirror's
     * only complete copy. Clearing under the discarded path cannot reach a live mirror,
     * because nothing else is ever filed there.
     *
     * The delete is [com.vscodroid.util.StorageManager.deleteRecursive] rather than
     * `File.deleteRecursively`, which asks `isDirectory` and `listFiles` and therefore
     * descends through a symlink pointing out of the mirror. On the launch pass that
     * was masked by [SafSyncEngine.holdsOnlyVouchedCopies] refusing any mirror holding
     * a link; a removal the user confirms has no such gate, and a mirror is routinely
     * a checked-out repository, so a link inside one is ordinary.
     *
     * Success is read off the filesystem rather than from a return value, because the
     * two callers need the same answer and one of them counts it.
     */
    private fun finishOff(discarded: File, originalPath: File): Boolean {
        StorageManager.deleteRecursive(discarded)
        if (discarded.exists()) {
            Logger.w(tag, "Could not finish removing ${discarded.name}; the next launch retries")
            return false
        }
        syncEngine.clearUploadsUnder(discarded)
        // The second clear is for entries set aside before the records learned to move
        // with the directory: theirs are still filed under the name the mirror had, and
        // nothing else will ever retire them. Guarded on the path being free, because
        // that is exactly the case this cannot tell apart. If the folder has been
        // re-granted, the records under that name may be the new mirror's, and the two
        // mistakes are not equal: keeping a dead record makes the next sync write a
        // mirror over a device copy that matches it anyway, while dropping a live one
        // lets a truncated device document overwrite the only complete copy. So the
        // ambiguous case keeps them, and the next removal of that mirror clears them
        // under its own discarded name.
        synchronized(grantLifecycleLock) {
            if (!Files.exists(originalPath.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                syncEngine.clearUploadsUnder(originalPath)
            }
        }
        return true
    }

    /** [setAside] then [finishOff], for a caller removing one entry on its own. */
    private fun discardEntry(root: File, name: String): Boolean {
        val originalPath = File(root, name.removePrefix(DISCARD_PREFIX))
        val discarded = setAside(root, name) ?: return false
        return finishOff(discarded, originalPath)
    }

    // -- Device folder storage, as the user sees it --

    /**
     * Ordinary local copies, largest first, including copies without a live grant.
     *
     * Each copy requires explicit confirmation because direct filesystem writes can
     * happen after any snapshot check. The compatibility field `reclaimable` is false.
     * Sizing walks the directories, so this must not run on the main thread.
     */
    fun listMirrors(): List<MirrorInfo> {
        val root = File(com.vscodroid.util.Environment.getSafMirrorsDir(context))
        val entries = root.listFiles()?.filter {
            it.isDirectory && isMirrorDirectoryName(it.name)
        } ?: return emptyList()

        val granted = context.contentResolver.persistedUriPermissions
            .map { getMirrorDir(it.uri).name }
            .toSet()
        val named = getPersistedFolders().associateBy { getMirrorDir(it.uri).name }

        return entries.map { dir ->
            MirrorInfo(
                hash = dir.name,
                displayName = named[dir.name]?.displayName,
                bytes = StorageManager.dirSize(dir),
                lastOpened = named[dir.name]?.lastOpened ?: 0L,
                granted = dir.name in granted,
                reclaimable = false,
            )
        }.sortedByDescending { it.bytes }
    }

    /**
     * Gives up the grant on the folder [hash] mirrors, and drops it from the recent
     * list.
     *
     * Called as part of removing a mirror, so that the two records of the folder go
     * with it. A recent-list entry that survives its mirror is an Open Recent row
     * pointing at a directory that is not there, and a grant that survives it makes the
     * launch pass treat the next mirror of that folder as live.
     *
     * It runs once the rename that commits the removal has succeeded, and not before
     * it. A rename the filesystem refuses leaves the mirror exactly where it was, while
     * nothing here can hand a released grant back: only the user re-picking the folder
     * can. Releasing first therefore turned a removal that did not happen into a folder
     * the app can no longer name, open or reclaim.
     *
     * A grant the system has already dropped is not an error here: the folder may have
     * been revoked in system settings, which is one of the ways a mirror becomes an
     * orphan in the first place.
     */
    fun releaseGrantFor(hash: String) = synchronized(grantLifecycleLock) {
        context.contentResolver.persistedUriPermissions
            .filter { getMirrorDir(it.uri).name == hash }
            .forEach { held ->
                try {
                    context.contentResolver.releasePersistableUriPermission(
                        held.uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                } catch (e: SecurityException) {
                    Logger.d(tag, "Grant for $hash was already gone")
                }
            }
        synchronized(recentFoldersLock) {
            saveRecentFolders(getPersistedFolders().filterNot { getMirrorDir(it.uri).name == hash })
        }
    }

    /**
     * Sets aside a local copy after explicit consent to delete potentially unique files.
     *
     * [force] records that consent. A snapshot cannot make an unconfirmed removal safe:
     * terminal and server writes do not share the mirror usage lock. The native caller
     * asks the user before setting this flag. A mirror claimed in this process remains
     * protected even when [force] is true.
     *
     * The ordinary directory rename commits the removal. Its sync record, upload
     * records and permission are retired under the same grant lifecycle lock before a
     * regrant can create a fresh copy. [sweepDiscardedMirrors] finishes deletion later.
     * Sizing walks the copy, so this must not run on the main thread.
     */
    fun reclaimMirror(hash: String, force: Boolean): Long {
        val root = File(com.vscodroid.util.Environment.getSafMirrorsDir(context))
        val dir = File(root, hash)
        // [hash] arrives from the page, so it is judged rather than trusted, and by the
        // same predicate that produced the listing it came from.
        if (!isMirrorDirectoryName(hash) || !dir.isDirectory) return RECLAIM_UNKNOWN
        if (!force) return RECLAIM_REFUSED

        val bytes = StorageManager.dirSize(dir)
        // The directory's rename is the commit point of the removal, so the directory's
        // rename is what decides whether one happened. Answering with the byte count
        // whatever the rename did reported a folder that is still on disk as reclaimed,
        // and by then the grant and the recent-list row were already gone, which is the
        // one state nothing in the app can undo or even name afterwards.
        //
        // Only the directory may decide. [setAside] answers null for an entry that is
        // not there as well as for a rename it could not make, and the record beside a
        // mirror is legitimately absent: it is written at the end of a sync, so a sync
        // that was interrupted leaves the directory without one.
        return synchronized(grantLifecycleLock) grant@ {
            val path = dir.absolutePath
            val discarded = synchronized(mirrorUsageLock) {
                if (path in mirrorsUsedThisProcess || path in mirrorsBeingRemoved) return@grant RECLAIM_IN_USE
                renameAside(root, hash)?.also { mirrorsBeingRemoved.add(path) }
            } ?: return@grant RECLAIM_FAILED
            try {
                syncEngine.renameUploadsUnder(dir, discarded)
                setAside(root, hash + SafSyncEngine.SYNCED_RECORD_SUFFIX)
                releaseGrantFor(hash)
                Logger.i(tag, "Set a device folder's local copy aside at the user's request")
                bytes
            } finally {
                synchronized(mirrorUsageLock) { mirrorsBeingRemoved.remove(path) }
            }
        }
    }

    /**
     * Deletes everything an earlier removal renamed out of the way, and reports how
     * many entries went.
     *
     * The second half of [reclaimMirror], and safe to call at any time from any
     * thread: a `discarded-` entry is this app's own leftover, already unreachable and
     * already judged, so there is no gate left to apply and nothing to race with. Only
     * names this app produces are touched, for the reason [reclaimRevokedMirrorsSync]
     * gives about `saf-mirrors` not being private scratch space.
     *
     * Failing part way costs disk until the next launch rather than correctness, which
     * is why the caller is free to run it on a thread it does not wait for.
     */
    fun sweepDiscardedMirrors(): Int {
        val root = File(com.vscodroid.util.Environment.getSafMirrorsDir(context))
        val leftovers = root.listFiles()?.map { it.name }?.filter {
            it.startsWith(DISCARD_PREFIX) && MIRROR_ENTRY.matches(it.removePrefix(DISCARD_PREFIX))
        } ?: return 0
        return leftovers.count { discardEntry(root, it) }
    }

    // -- Sync Coordination --

    /**
     * Syncs the contents of a SAF folder tree to a local mirror directory.
     *
     * @param safUri The tree URI from the SAF folder picker.
     * @param onProgress Callback with (filesDone, totalFiles) for progress reporting.
     * @return The local mirror directory containing the synced files.
     * @throws SecurityException if the URI permission has been revoked.
     * @throws FolderBusyException if the folder is busy: see [SafSyncEngine.initialSync].
     */
    suspend fun syncToLocal(
        safUri: Uri,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): File {
        if (!hasPersistedPermission(safUri)) {
            // Named by its mirror, like every other line about this folder. The
            // interpolation here is the one channel redacting the log lines could
            // not close: `MainActivity.openSafFolder` catches this and passes the
            // throwable itself to `Logger.e`, which prints the message, and none
            // of the three severities is gated on a debuggable build. Redacting
            // the caller alone would have left the same tree URI arriving by the
            // other route.
            throw SecurityException("Permission revoked for ${getMirrorDir(safUri).name}")
        }

        val mirrorDir = getMirrorDir(safUri)
        mirrorDir.mkdirs()

        syncEngine.initialSync(safUri, mirrorDir, onProgress)
        updateLastOpened(safUri)

        // By mirror name, for the reason [persistPermission] gives: the tree URI is the
        // user's own directory path and this level ships.
        Logger.i(tag, "Sync complete for ${mirrorDir.name}")
        return mirrorDir
    }

    /**
     * Starts a FileObserver on the mirror directory that writes changes back to SAF.
     */
    fun startFileWatcher(localMirrorDir: File, safUri: Uri) {
        if (!claimMirrorOpen(localMirrorDir)) throw FolderBusyException()
        syncEngine.startWatching(localMirrorDir, safUri)
        Logger.i(tag, "File watcher started for: ${localMirrorDir.absolutePath}")
    }

    /**
     * Stops the active file watcher. Call this when switching folders or on destroy.
     */
    fun stopFileWatcher() {
        syncEngine.stopWatching()
    }

    /**
     * Stops the active file watcher and refuses every later start on this engine. Call
     * this on destroy; [stopFileWatcher] is the one for a folder switch.
     *
     * The difference is whether anything is allowed to follow. This manager belongs to
     * one Activity and the next one builds its own, so after a teardown no caller can
     * reach this engine to stop it a second time; a start that was already inside it
     * when the teardown ran would leave observers and a write-back thread that outlive
     * the process's ability to end them.
     */
    fun shutdownFileWatcher() {
        syncEngine.shutdown()
    }

    // -- Mirror Directory --

    /**
     * Returns a deterministic local directory for mirroring a SAF URI.
     * Delegates to [Environment.getSafMirrorDir] for consistent path resolution.
     */
    fun getMirrorDir(safUri: Uri): File {
        return File(com.vscodroid.util.Environment.getSafMirrorDir(context, safUri))
    }

    /**
     * A path to [mirrorDir] whose last segment is the device folder's own name, or null
     * when none can be made.
     *
     * The workbench names a folder after the last segment of its path, in the Explorer
     * and in the window title, so a copy opened at `saf-mirrors/<hash>` was shown as its
     * hash. The copy itself stays where it is: the reclaim pass, the sync record beside
     * it and the upload journal all name it by that hash, and a folder renamed on a
     * provider whose ids are stable keeps its grant, so a copy named after the folder
     * would have to move under a running watcher. This makes
     * `saf-mirrors/by-name/<hash>/<name>`, a link to the copy, for the workbench to open
     * instead. The hash one level up keeps two folders of the same name apart, and
     * [mirrorNameFor] and [folderForOpenedPath] read the copy back out of the path.
     *
     * Relative, `../../<hash>`, so it still resolves if the app's data is moved to other
     * storage. An existing link to this copy is answered as it stands; anything else
     * under that name is left alone and answered with null, which keeps the folder on
     * its hash path. A folder renamed on the device gets a link for its new name beside
     * the old one rather than in place of it, because the old one may be the path the
     * page is on. The launch pass removes both once the copy has gone.
     *
     * ⚠️ Disk work, so not on the main thread.
     */
    fun namedPathFor(mirrorDir: File, displayName: String): File? {
        val name = nameSegment(displayName) ?: return null
        val link = File(File(File(mirrorDir.parentFile, NAMED_DIR), mirrorDir.name), name)
        val path = link.toPath()
        val target = Paths.get("..", "..", mirrorDir.name)
        fun ours(): File? =
            link.takeIf { Files.isSymbolicLink(path) && Files.readSymbolicLink(path) == target }
        return try {
            when {
                Files.isSymbolicLink(path) -> ours()
                Files.exists(path, LinkOption.NOFOLLOW_LINKS) -> null
                else -> {
                    Files.createDirectories(path.parent)
                    try {
                        Files.createSymbolicLink(path, target)
                    } catch (e: FileAlreadyExistsException) {
                        // A page load and a picked folder can name the same copy at once.
                        return ours()
                    }
                    link
                }
            }
        } catch (e: Exception) {
            // The digest and the exception's type only: the path holds the folder's name.
            Logger.w(tag, "Could not name the copy ${mirrorDir.name}: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * The named path [mirrorDir]'s copy already has, when it has exactly one, and null
     * otherwise. Makes nothing.
     *
     * For an open whose name query was not answered, which has nothing true to name a link
     * after: [namedPathFor] would take the fallback for a name and, where that is not the
     * folder's own, make a second link, a second path for the workbench, which keeps open
     * editors, unsaved changes and terminals per path. The one link the copy has is the
     * path the page was on. With none, or with two or more, as a folder renamed on the
     * device has, there is no link to take, or no telling which the page was on, and the
     * copy opens by its hash.
     *
     * Read as [namedPathFor] reads a link: one to `../../<hash>`, under a directory that
     * is a directory and not a link put in its place, and no other entry beside it.
     *
     * ⚠️ Disk work, so not on the main thread.
     */
    fun existingNamedPath(mirrorDir: File): File? {
        val dir = File(File(mirrorDir.parentFile, NAMED_DIR), mirrorDir.name)
        val target = Paths.get("..", "..", mirrorDir.name)
        return try {
            if (!Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
            val link = dir.listFiles()?.singleOrNull() ?: return null
            val path = link.toPath()
            link.takeIf { Files.isSymbolicLink(path) && Files.readSymbolicLink(path) == target }
        } catch (e: Exception) {
            // The digest and the exception's type only: the path holds the folder's name.
            Logger.w(tag, "Could not read the names of ${mirrorDir.name}: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * Records how the workbench has [folder]'s copy open, from [opened], the path it
     * opened.
     *
     * A page on the named path is noted, which [wasShownByName] answers from then on. A
     * page on the hash path gets a named path made for it, so that the bundled
     * extension can offer to move the page there, unless the folder has no name a
     * provider answered ([SafFolderInfo.named]), which waits for an open that is.
     */
    fun noteOpened(opened: String, folder: SafFolderInfo) {
        val mirror = File(folder.mirrorPath)
        if (namedRootOf(opened, mirror.parent.orEmpty()) == null) {
            // Not for a folder whose name no provider has answered: its label is the
            // tree's document id, and a link named for that is a second path for the copy.
            if (folder.named) namedPathFor(mirror, folder.displayName)
            return
        }
        try {
            shownMark(mirror).createNewFile()
        } catch (e: IOException) {
            Logger.w(tag, "Could not note that ${mirror.name} opened by name: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Whether a page has opened [mirrorDir]'s copy by its named path.
     *
     * The workbench keeps open editors, unsaved changes and terminals per path, so the
     * same folder under its hash path and under its named path is two workspaces to it.
     * A copy made before named paths existed may hold unsaved changes under its hash
     * path, and opening it straight by name would leave them behind there with nothing
     * to say so. This is what lets [com.vscodroid.MainActivity] open such a copy by its
     * hash, where the extension offers the move, until the folder has been seen by name.
     */
    fun wasShownByName(mirrorDir: File): Boolean = shownMark(mirrorDir).exists()

    private fun shownMark(mirrorDir: File): File =
        File(File(mirrorDir.parentFile, NAMED_DIR), mirrorDir.name + SHOWN_SUFFIX)

    /**
     * Resolves a human-readable display name for a SAF tree URI, falling back to
     * [fallbackDisplayName] where the provider does not answer.
     *
     * For the shown name only. A caller that makes something from the name, as
     * [namedPathFor] does a path, has to ask [queryDisplayName] and tell an answer from
     * the fallback, which is the tree's document id and names the folder only where the
     * provider spells its ids as paths.
     */
    fun getDisplayName(safUri: Uri): String =
        queryDisplayName(safUri) ?: fallbackDisplayName(safUri)

    /**
     * The folder's name as its provider answers it, or null where the query returned no
     * row, returned no name, or failed.
     *
     * Not [getDisplayName], whose fallback is indistinguishable from an answer: handed to
     * [namedPathFor] it made a second, differently named link for the same copy, and the
     * workbench, which keeps open editors, unsaved changes and terminals per path, was
     * moved onto it from the one it had them under.
     *
     * ⚠️ A provider round trip, so not on the main thread.
     */
    fun queryDisplayName(safUri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(safUri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(safUri, docId)
            val cursor = context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )
            cursor?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) {
            Logger.d(tag, "Failed to resolve display name: ${e.message}")
            null
        }
    }

    // -- Internal --

    private fun addToRecentFolders(uri: Uri) {
        // Resolved before the lock: it is a provider round trip, and a network or MTP
        // provider can make it a long one. Nothing in the list is read to compute it.
        //
        // The provider's answer and not [getDisplayName]'s fallback, which is the tree's
        // document id: stored as the folder's name it was the label in Open Recent, and
        // [noteOpened] made a link from it, a second path for the same copy.
        val answered = queryDisplayName(uri)?.takeIf { it.isNotBlank() }
        val dropped = synchronized(recentFoldersLock) {
            val folders = getPersistedFolders().toMutableList()

            // The name the provider last gave stands where it does not answer now, and
            // without one the entry is left unnamed.
            val name = answered ?: folders.firstOrNull { it.uri == uri && it.named }?.displayName

            // Remove existing entry for this URI (will re-add with updated timestamp)
            folders.removeAll { it.uri == uri }

            folders.add(
                0,
                SafFolderInfo(
                    uri = uri,
                    displayName = name ?: fallbackDisplayName(uri),
                    lastOpened = System.currentTimeMillis(),
                    mirrorPath = getMirrorDir(uri).absolutePath,
                    named = name != null,
                )
            )

            // Keep at most MAX_RECENT entries
            val (trimmed, evicted) = splitRecent(folders, MAX_RECENT)
            saveRecentFolders(trimmed)
            evicted
        }

        // The grant goes with the list entry, and until it did there was no way
        // out at all: the reclaim pass judges a mirror by whether a permission is
        // still persisted, so a folder that fell off this list kept its grant,
        // looked live for ever, and its mirror could never be reclaimed by
        // anything the app does.
        //
        // Safe to do here only because of what the reclaim pass refuses to delete,
        // and that clause has been wrong once already. It used to read "a mirror
        // holding a write that never reached the device", meaning the upload
        // journal, which records write-backs that were ATTEMPTED and failed. A
        // file never queued for write-back at all leaves no journal entry, and
        // several routes end there: anything under SKIP_DIRECTORIES, so a `.git`
        // from a terminal clone; a file below a directory past the watch cap;
        // anything written while no watcher ran; and a copy the initial sync kept
        // for being newer than the device document. Every one of those is the
        // user's only copy, and eviction at the eleventh folder deleted it.
        //
        // The gate now asks the record instead, which has to prove a mirror is
        // disposable rather than look for evidence that it is not. See
        // SafSyncEngine.holdsOnlyVouchedCopies.
        for (gone in dropped) {
            try {
                context.contentResolver.releasePersistableUriPermission(
                    gone.uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                Logger.i(tag, "Released the grant for a folder that left the recent list")
            } catch (e: SecurityException) {
                Logger.d(tag, "Grant already gone for ${gone.displayName}")
            }
        }
    }

    private fun updateLastOpened(uri: Uri) = synchronized(recentFoldersLock) {
        val folders = getPersistedFolders().toMutableList()
        val index = folders.indexOfFirst { it.uri == uri }
        if (index >= 0) {
            folders[index] = folders[index].copy(lastOpened = System.currentTimeMillis())
            saveRecentFolders(folders)
        }
    }

    private fun saveRecentFolders(folders: List<SafFolderInfo>) {
        val array = JSONArray()
        folders.forEach { f ->
            array.put(JSONObject().apply {
                put("uri", f.uri.toString())
                // Only a name the provider gave. An entry without one is read back as
                // unnamed, with the fallback for a label, and waits for an open that
                // is answered.
                if (f.named) put("name", f.displayName)
                put("lastOpened", f.lastOpened)
            })
        }
        prefs.edit { putString(KEY_RECENT_FOLDERS, array.toString()) }
    }

    companion object {
        private const val PREFS_NAME = "vscodroid_saf"
        private const val KEY_RECENT_FOLDERS = "recent_folders"
        private const val MAX_RECENT = 10

        // A later take must follow the complete old release or mirror set-aside.
        private val grantLifecycleLock = Any()
        private val mirrorUsageLock = Any()
        private val mirrorsUsedThisProcess = mutableSetOf<String>()
        private val mirrorsBeingRemoved = mutableSetOf<String>()

        /**
         * Serialises every read-modify-write of the recent list.
         *
         * The list is one JSON string in one preference, and four operations rewrite it
         * from a value they read first: the prune inside [getPersistedFolders],
         * [addToRecentFolders], [updateLastOpened] and [releaseGrantFor]. Three threads
         * reach those. The UI thread picks a folder, a Dispatchers.IO thread finishes a
         * sync, and the WebView's bridge thread asks for the recent list and removes
         * device-folder copies. Interleaved, the later writer saves a list it read
         * before the earlier one wrote: a folder just picked drops out of Open Recent,
         * or a folder whose mirror has just been removed keeps a row pointing at a
         * directory that is not there.
         *
         * In the companion, like [SafSyncEngine]'s journal lock and for the reason given
         * there: this class is one per Activity, a recreated Activity builds a second one
         * while the first one's sync is still finishing on the IO dispatcher, and both
         * address the one preferences file.
         */
        private val recentFoldersLock = Any()

        /**
         * How many persisted grants this process has taken, counted only so that two
         * readings of them can be told apart.
         *
         * The prune inside [getPersistedFolders] judges the saved list against a reading
         * of the system server's grants, and that reading is deliberately taken outside
         * [recentFoldersLock], so an ordinary listing does not hold the monitor across a
         * binder round trip. A call nested inside one of the read-modify-writes still
         * does, and [getPersistedFolders] says which ones and what it costs.
         * Taking the reading first leaves one ordering the lock used to forbid: a
         * reading older than the list
         * it is judging, which is what a grant taken between the two produces, and which
         * would prune away the row the picker has just written. This is bumped between
         * the grant and the row (see [persistPermission]), so a reader that took its
         * reading before the grant sees a different value once it holds the monitor, and
         * declines to judge.
         *
         * Only a grant TAKEN is counted. A grant released moves the other way: the
         * reading still names it, so the row survives one more listing and the next one
         * prunes it, which is the same one-listing delay a revocation in system settings
         * already has.
         *
         * Never reset, so no path can leave it stale: it counts up, comparisons are for
         * equality between two reads on one thread, and the whole of it dies with the
         * process. In the companion for the reason [recentFoldersLock] gives, and because
         * the grants it counts belong to the process rather than to one activity's
         * manager.
         */
        private val grantsTaken = AtomicLong(0)

        /**
         * Answers of [reclaimMirror] that are not a byte count.
         *
         * [RECLAIM_FAILED] is separate from the other two because the folder it names
         * is untouched rather than gone: the user confirmed, the mirror is where it was,
         * and asking again is worth something. Folding it into either of the others
         * would tell the user the copy is not there, or that it holds work the device
         * does not, neither of which is true.
         */
        internal const val RECLAIM_REFUSED = -1L
        internal const val RECLAIM_UNKNOWN = -2L
        internal const val RECLAIM_FAILED = -3L
        internal const val RECLAIM_IN_USE = -4L

        /**
         * Why a mirror the user asked to remove must not be removed now, as the string
         * resource that says so, or null when nothing this side knows about stands in
         * the way.
         *
         * The question is whether the app knows anything still using this mirror.
         * Explicit confirmation is separate and never overrides this refusal. Inputs
         * from the Activity provide a more specific reason than the manager's shared
         * process-wide usage set.
         *
         * A mirror the editor still has open is not an inconvenience to delete, it is
         * device data loss. The observers are live: a `FileObserver.DELETE` reaches
         * `handleMirrorEvent`, becomes a DELETE write-back job and calls `deleteFromSaf`
         * on the matching document, so a recursive delete of a watched mirror is
         * replayed onto the user's real files through the provider. Nothing about the
         * delete looks unusual from the watcher's side; a person deleting files is
         * exactly what it exists to carry across.
         *
         * [watchedThisProcess] is the wide one and the others are narrower cases of it
         * that produce a better sentence. It is deliberately never cleared. Closing a
         * folder stops its observers, but `SafSyncEngine.stopWatching` waits only
         * `DRAIN_GRACE_MS` for the write-back worker and then leaves it running rather
         * than throwing away writes the user is expecting on the device, so a drain can
         * still be streaming out of a mirror the app considers closed, and every write
         * it performs opens the device document with `"wt"`, which truncates at open. A
         * drain only ever touches the mirror it was started for, so refusing every
         * mirror this process has watched is what puts it out of reach, and a restart
         * is what makes the folder removable. Removing any of the three narrower checks
         * degrades the message rather than opening the hole; removing this one opens
         * it.
         */
        @StringRes
        internal fun reclaimRefusal(
            hash: String,
            watchedMirror: String?,
            syncingMirror: String?,
            openWorkspaceMirror: String?,
            watchedThisProcess: Set<String>,
        ): Int? = when (hash) {
            watchedMirror -> RECLAIM_FOLDER_OPEN
            syncingMirror -> RECLAIM_FOLDER_OPENING
            openWorkspaceMirror -> RECLAIM_FOLDER_OPEN
            in watchedThisProcess -> RECLAIM_FOLDER_THIS_SESSION
            else -> null
        }

        /**
         * The three refusals above, as string resources rather than sentences.
         *
         * They cross the bridge and the bundled extension shows whichever comes
         * back verbatim, so they are user-facing text, and a sentence written in
         * Kotlin is the same in every locale for ever. Nothing reported that:
         * `check-translatable-strings.py` finds a literal only where the literal
         * is written at the sink, and these leave through a return value, which
         * its own docstring names as the hole it has.
         *
         * The ids and not the text, so this stays a pure predicate a JVM test can
         * exercise with no Context: what belongs to which case is decided here and
         * the words are resolved by the Activity that has one. Resource ids are
         * never zero, so null still means "no refusal".
         */
        @StringRes
        internal val RECLAIM_FOLDER_OPEN = R.string.saf_mirror_folder_open
        @StringRes
        internal val RECLAIM_FOLDER_OPENING = R.string.saf_mirror_folder_opening
        @StringRes
        internal val RECLAIM_FOLDER_THIS_SESSION = R.string.saf_mirror_folder_this_session

        /**
         * The mirror [path] sits in, or null when it is not under [mirrorsRoot].
         *
         * The separator rule is [folderForOpenedPath]'s and is load-bearing for the
         * same reason: mirror names are a hash prefix, so one being a prefix of another
         * is ordinary, and a bare `startsWith` would name the wrong folder. Here the
         * cost of naming the wrong one is refusing to remove a mirror that is free
         * while allowing one that is open.
         */
        internal fun mirrorNameFor(path: String?, mirrorsRoot: String): String? {
            val prefix = mirrorsRoot + File.separator
            if (path == null || !path.startsWith(prefix)) return null
            // A named path is that same copy, and the guard that refuses to remove
            // the folder the workbench has open has to see it as such.
            return mirrorSpelling(path, mirrorsRoot).removePrefix(prefix)
                .substringBefore(File.separatorChar)
                .takeIf { it.isNotEmpty() }
        }

        /**
         * Where the copies' named paths live, inside `saf-mirrors` and beside the copies:
         * `by-name/<hash>/<name>`, a link to `../../<hash>`. See [namedPathFor].
         *
         * Inside rather than in a directory of its own, so that a reader of a workspace
         * path that has not learned about named paths still sees one under `saf-mirrors`
         * and treats it as a copy: `rememberedFolderToReopen` then asks for its grant
         * rather than reopening it as an ordinary project. The reclaim pass, the storage
         * screen and the removal sweep pass over it, since it is not a name of the shape
         * a copy has.
         */
        internal const val NAMED_DIR = "by-name"

        /**
         * The mark beside a copy's named paths, `by-name/<hash>.shown`, saying that a page
         * has opened the copy by name. See [wasShownByName].
         */
        internal const val SHOWN_SUFFIX = ".shown"

        /**
         * What [getDisplayName] answers where the provider does not: the tree's last path
         * segment, which is its document id: `primary:Documents/notes` on the platform
         * provider, whose last part is the folder's name, and whatever the provider chose
         * elsewhere. Never blank, because a list and a notice print it as it stands.
         */
        internal fun fallbackDisplayName(safUri: Uri): String =
            safUri.lastPathSegment?.takeIf { it.isNotBlank() } ?: "Unknown"

        /** The longest name the filesystems Android keeps app data on take, in bytes. */
        private const val MAX_NAME_BYTES = 255

        /**
         * [displayName] as one segment of a path, or null when it cannot be one.
         *
         * A name is shown by the workbench and spelled into URLs, terminals and tools, so
         * a control character is replaced and the name is cut to what a filesystem takes.
         * The part after the last `/` is used: a provider whose name query fails is
         * answered with the tree's document id, `primary:Documents/notes`, and that is
         * the folder `notes`. `.` and `..` name other directories, so they cannot be used.
         */
        internal fun nameSegment(displayName: String): String? {
            val name = buildString {
                displayName.substringAfterLast('/').forEach { append(if (it.isISOControl()) '_' else it) }
            }
            if (name.isBlank() || name == "." || name == "..") return null
            var cut = name
            while (cut.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) {
                cut = cut.substring(0, cut.offsetByCodePoints(cut.length, -1))
            }
            return cut
        }

        /**
         * The named path [path] lies on, with the copy it names, or null when it lies on
         * none: `<mirrorsRoot>/by-name/<hash>/<name>` and `<hash>`.
         */
        internal fun namedRootOf(path: String, mirrorsRoot: String): Pair<String, String>? {
            val prefix = mirrorsRoot + File.separator + NAMED_DIR + File.separator
            if (!path.startsWith(prefix)) return null
            val parts = path.removePrefix(prefix).split(File.separatorChar, limit = 3)
            if (parts.size < 2 || !isMirrorDirectoryName(parts[0]) || parts[1].isEmpty()) return null
            return prefix + parts[0] + File.separator + parts[1] to parts[0]
        }

        /**
         * [path] spelled through the copy itself when it lies on a named path, and as it
         * stands otherwise. Textual, so it answers for a link that is not there.
         */
        internal fun mirrorSpelling(path: String, mirrorsRoot: String): String {
            val (root, hash) = namedRootOf(path, mirrorsRoot) ?: return path
            return mirrorsRoot + File.separator + hash + path.removePrefix(root)
        }

        /**
         * Splits the recent list into what is kept and what falls off the end.
         *
         * Returned as a pair rather than trimmed in place because the tail is not
         * waste: each entry there still holds a persisted permission. Releasing an
         * evicted entry keeps the grant budget bounded; its local copy remains on
         * disk until the user explicitly removes it from the storage screen.
         */
        internal fun splitRecent(
            folders: List<SafFolderInfo>,
            max: Int,
        ): Pair<List<SafFolderInfo>, List<SafFolderInfo>> =
            folders.take(max) to folders.drop(max)

        /**
         * The folder in [folders] whose mirror contains [opened], if any. The one
         * production caller passes the persisted list.
         *
         * The subdirectory case is not a nicety. Open Folder can point at a
         * directory *inside* a mirror, and the watcher's root has to be the
         * mirror root or every relative path the sync computes is resolved
         * against the wrong base.
         *
         * The separator matters for the same reason it does in the reclaim gate:
         * mirror names are a hash prefix, so one being a prefix of another is
         * ordinary, and a bare `startsWith` would match the wrong folder.
         *
         * A named path ([namedPathFor]) is read as the copy it names. Missing it would
         * leave a folder opened by name with no watcher, every save staying in the copy.
         */
        internal fun folderForOpenedPath(
            folders: List<SafFolderInfo>,
            opened: String,
        ): SafFolderInfo? = try {
            val canonicalOpened = File(opened).canonicalPath
            folders.firstOrNull {
                val mirror = File(it.mirrorPath).canonicalFile
                val path = mirrorSpelling(canonicalOpened, mirror.parent.orEmpty())
                path == mirror.path || path.startsWith(mirror.path + File.separator)
            }
        } catch (_: IOException) {
            null
        }

        /**
         * An entry in `saf-mirrors` that this app created: a mirror directory, or the
         * sync record beside it.
         *
         * [com.vscodroid.util.Environment.getSafMirrorDir] names a mirror after the first
         * six bytes of a digest, so twelve hex characters. Pinned by
         * `SafMirrorReclamationTest`, because the length lives there and the consequence
         * of the two drifting apart is a reclamation pass that stops recognising its own
         * mirrors, or starts recognising files it did not write.
         */
        internal val MIRROR_ENTRY =
            Regex("^[0-9a-f]{12}(${Regex.escape(SafSyncEngine.SYNCED_RECORD_SUFFIX)})?$")

        /**
         * The record's own scratch file, which [MIRROR_ENTRY] deliberately does not
         * match: it is not half of a mirror and must never be counted as one, only
         * cleared away once the mirror it belonged to has gone.
         */
        internal val MIRROR_RECORD_PARTIAL = Regex(
            "^[0-9a-f]{12}" +
                Regex.escape(SafSyncEngine.SYNCED_RECORD_SUFFIX + SafSyncEngine.PARTIAL_SUFFIX) +
                "$"
        )

        /**
         * Marks an entry renamed out of the way and awaiting deletion. Reclaimed
         * unconditionally on a later pass: nothing but this method creates one, and
         * whatever it named is already unreachable.
         */
        internal const val DISCARD_PREFIX = "discarded-"

        /**
         * Whether [name] is a mirror directory rather than anything else in
         * `saf-mirrors`.
         *
         * Narrower than [MIRROR_ENTRY] by exactly one case, and the difference is why
         * this is a predicate rather than a use of that pattern. The pattern matches
         * both `<hash>` and `<hash>.synced`, because the reclaim pass has to recognise
         * both halves of a mirror and remove them together. The user-facing side has
         * the opposite need: the record is not a folder anybody opened, so listing it
         * claims two folders where there is one, and accepting it as a removal target
         * would set aside a record while leaving its mirror.
         *
         * Not covered by an `isDirectory` test at the call sites, which is the tempting
         * simplification and was measured to be wrong: `saf-mirrors` is exported into
         * every terminal as `SAF_MIRRORS_DIR`, so a directory can be created there
         * under any name at all, this one included.
         */
        internal fun isMirrorDirectoryName(name: String): Boolean =
            MIRROR_ENTRY.matches(name) && !name.endsWith(SafSyncEngine.SYNCED_RECORD_SUFFIX)

        /** How long one write-back failure notice suppresses the next. */
        internal const val FAILURE_NOTICE_INTERVAL_MS = 10_000L

        /**
         * The stamp of a session in which nothing has been announced yet.
         *
         * Tested for rather than subtracted from, and that became necessary with the
         * clock. Under wall time zero was further back than any interval on its own;
         * under the monotonic clock the reading is milliseconds since boot, so a device
         * that has been up for less than the interval would have found the FIRST failure
         * of a session too close to zero to be worth saying, which is the one notice
         * that always has to be given.
         */
        internal const val NEVER_ANNOUNCED = 0L

        /**
         * Whether a failure at [now] is far enough from [lastAnnouncedAt] to be said.
         *
         * A function rather than an inline comparison so the rule can be asserted: the
         * wiring around it reaches a Toast, which no JVM test here can see.
         *
         * [now] comes from the monotonic clock; see [onWriteBackFailed] for why it is
         * not the wall clock.
         */
        internal fun shouldAnnounce(now: Long, lastAnnouncedAt: Long): Boolean =
            lastAnnouncedAt == NEVER_ANNOUNCED ||
                now - lastAnnouncedAt >= FAILURE_NOTICE_INTERVAL_MS
    }
}

/**
 * Data class representing a SAF folder that the user has granted access to.
 *
 * [displayName] is never empty, so a list or a notice can print it as it stands. Where no
 * provider has answered for the folder's name, [named] is false and [displayName] is the
 * tree's own last path segment, [SafStorageManager.fallbackDisplayName], which reads as a
 * name and is a document id. Anything that makes something from the name, as
 * [SafStorageManager.noteOpened] does a path, has to ask [named] first.
 */
data class SafFolderInfo(
    val uri: Uri,
    val displayName: String,
    val lastOpened: Long,
    val mirrorPath: String,
    val named: Boolean = true,
)

/**
 * One device folder's local copy, as the storage screen has to describe it.
 *
 * [displayName] and [lastOpened] come from the recent list and are therefore absent
 * for an orphan: the grant and the list entry are released together when a folder
 * falls off the end of [SafStorageManager.MAX_RECENT], so the mirror that survives
 * has no name anywhere in the app. That is precisely the mirror worth showing, so
 * the absence is carried rather than filled in with the hash.
 *
 * [reclaimable] remains false for compatibility with callers that use it to select
 * a destructive confirmation. Copies may contain files that never reached the device,
 * and direct writes can invalidate a snapshot before removal commits.
 */
data class MirrorInfo(
    val hash: String,
    val displayName: String?,
    val bytes: Long,
    val lastOpened: Long,
    val granted: Boolean,
    val reclaimable: Boolean,
)
