package com.vscodroid.setup

import android.system.Os
import com.vscodroid.util.Logger
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.cert.Certificate
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

// Atomic file I/O, write-lock serialization, symlink helpers, durability flush, and stray-temp sweeps for setup.

/**
 * Whether [file] is itself a symbolic link.
 *
 * There were two of these in this package, asking two different questions, and
 * that is the defect rather than any symptom. This one now asks `lstat`, the way
 * `ToolchainManager` always did. The other asked whether `canonicalPath`
 * differed from `absolutePath`, which is not a question about [file]:
 * canonicalisation resolves links anywhere along the path, so it answers
 * "does this path traverse any symlink", and it answers that about the whole
 * path shape rather than about the last component.
 *
 * Be precise about what that did and did not cost, because the obvious story is
 * wrong and was written here before it was measured. On API 36 and 37
 * `/data/user/0` is a separate mount rather than a link to `/data/data` --
 * measured on both emulators, `ls -ld` shows a directory and `/proc/mounts`
 * shows its own `ext4` entry -- so canonicalisation moves an app-private path
 * nowhere at all. The old rule therefore answered false for a regular file and
 * true for a real link: the right answers, at the only call site that uses it
 * ([FirstRunSetup.createNpmWrappers]), by coincidence. No stale `npm` wrapper
 * survived because of it. An earlier version of this comment claimed one did.
 *
 * The coincidence is the problem. It is rented from a path shape the platform
 * owns and we do not, it has been otherwise on Android before, and it fails in
 * both directions when it changes: a genuinely symlinked parent makes every
 * regular file under it read as a link, so the delete this guards stops
 * happening; and `canonicalPath` throws on paths `lstat` handles, which the
 * catch below turns into "not a link" for something that is one.
 *
 * `lstat` is the one call that does not follow the final component, so it needs
 * none of that to be true. The mode test is split out so it can be pinned by a
 * test; see [isSymlinkMode].
 */
internal fun isSymlink(file: File): Boolean =
    try {
        isSymlinkMode(Os.lstat(file.absolutePath).st_mode)
    } catch (e: Exception) {
        false
    }


/**
 * Whether an `st_mode` as returned by `lstat` describes a symbolic link.
 *
 * Written against the raw POSIX file-type constants rather than
 * `OsConstants.S_ISLNK`, and that is a testability decision worth stating
 * plainly. `Os.lstat` cannot run in a JVM unit test, so the only part of this
 * predicate a test can reach is the arithmetic on a mode it supplies itself --
 * and `S_ISLNK` is a method on a stubbed platform class, so a test calling it
 * gets "not mocked" rather than an answer. The constants are no better: this
 * module sets no `isReturnDefaultValues`, and `OsConstants.S_IFMT` is not a
 * compile-time constant, so a unit test reads it as 0. A mask of zero makes
 * every mode compare equal, which is the same "true for everything" failure
 * this function exists to end.
 *
 * The values are fixed by the Linux ABI that Bionic implements: `S_IFMT` is
 * 0170000 and `S_IFLNK` is 0120000.
 */
internal fun isSymlinkMode(stMode: Int): Boolean = (stMode and 0xF000) == 0xA000


/**
 * Whether [text] holds nothing but NULs and whitespace: what a first write cut short leaves, as zero bytes, or on
 * f2fs after a power loss as the file's length in NULs. Neither is a file anyone wrote, and a NUL is not whitespace,
 * so `isBlank` let the second stand for good.
 */
internal fun isLeftBlank(text: String): Boolean = text.all { it == '\u0000' || it.isWhitespace() }


/**
 * Writes [dest] through a temporary file, so a failure leaves no partial file
 * under the name everything else checks for.
 *
 * Writing straight to the destination is what made a failed extraction
 * indistinguishable from a complete one: the bytes written before the failure
 * stay on disk, and every caller decides a file is present with `exists()`,
 * which a truncated file satisfies. The reconciliation added for #18 could
 * therefore accept a half-written interpreter runtime and never look again.
 *
 * A uniquely named sibling is created and opened exclusively, so an existing
 * backup, link or payload file is never claimed as scratch. Its short name also
 * works when the destination already reaches the filesystem's filename limit.
 * The rename stays within one filesystem. A process killed during the write can
 * leave its unique temporary file; an ordinary failure removes only the file this
 * invocation successfully opened.
 *
 * The exclusion is per DESTINATION, and that is not a refinement. One monitor
 * for every write put a 118 MiB inflate on the same lock as a one-line config
 * rewrite: a second SplashActivity entering while the first instance's
 * extraction was still running (Home and relaunch, or any config change the
 * manifest does not declare, neither of which stops the extraction, which
 * checks for cancellation nowhere) reached `createBashEnvFile` on the MAIN
 * thread and blocked there for the whole of one asset copy, before the first
 * frame. The contending pair this exists for is two threads racing for the same
 * file, and destinations that are not the same file never had anything to say
 * to each other.
 *
 * The read that decides WHAT to write is outside this, and the lock does not
 * reach it. Every appender to `.bashrc` reads the file, decides, and only then
 * calls here, so two threads can read the same bytes and the later write wins,
 * dropping the earlier one's block. It is bounded rather than closed: each
 * appender re-tests its own guard string on the next launch and appends again,
 * so the cost is one launch without one block, not a permanent loss. Closing it
 * would mean computing the payload under this monitor, which is a shape the four
 * appenders do not have; the bound is written down here so a reader does not
 * take the word atomic for more than it covers.
 *
 * @param onError told why a write failed, in one sentence, for a caller that has
 *   somewhere to put it. Nothing is logged here: this is reached from every
 *   config writer in the file, each of which already logs its own failure, and
 *   the one that needs the CAUSE is extraction, which carries it into the
 *   message the splash screen shows.
 *
 * @return true if [dest] now holds what [write] produced. On false, [dest] is
 *   untouched -- it keeps its previous contents, or stays absent.
 *
 * [throughLink] writes into the file a link at [dest] names instead of replacing the link, for the
 * files a person owns and may link elsewhere, and never replaces a link, even one to nothing (see
 * [linkTarget]). The default replaces it, which the extraction of the app's own files relies on.
 */
internal fun writeAtomically(
    dest: File,
    onError: ((String) -> Unit)? = null,
    throughLink: Boolean = false,
    write: (OutputStream) -> Unit,
): Boolean {
    val target = if (throughLink) linkTarget(dest) else dest
    if (target == null) {
        onError?.invoke("${dest.name} is a link that cannot be followed to a file")
        return false
    }
    return writeAtomicallyTo(target, onError, write)
}


/**
 * [file], or what a link at [file] names, followed link by link as the kernel follows it, or null when the links
 * lead round in a circle or one cannot be read. A dotfile a person keeps in a repository and links into the home directory is replaced
 * by a copy when the rename lands on the link, and the repository's file is then no longer the one the shell
 * reads. So no link is ever the destination: a link to a file that has gone names where it would be, and the
 * write makes it there, or fails without a trace when the folder it would be in has gone too, which is what a
 * renamed dotfiles folder or a device folder's copy the app took back leaves. A link to a directory fails at the
 * rename.
 */
private fun linkTarget(file: File): File? {
    var path = file.toPath()
    if (!Files.isSymbolicLink(path)) return file
    repeat(MAX_LINK_HOPS) {
        path = try { path.resolveSibling(Files.readSymbolicLink(path)) } catch (_: IOException) { return null }
        if (!Files.isSymbolicLink(path)) {
            // One canonical spelling for a file that is there, so two writers through different links share a lock.
            return try { path.toRealPath().toFile() } catch (_: IOException) { path.toFile() }
        }
    }
    return null
}


/** How many links the kernel follows before it answers ELOOP. */
private const val MAX_LINK_HOPS = 40


private fun writeAtomicallyTo(
    dest: File,
    onError: ((String) -> Unit)?,
    write: (OutputStream) -> Unit,
): Boolean {
    val path = lockKey(dest)
    val lock = claimWriteLock(path)
    try {
        synchronized(lock) {
            val prefix = ".vscodroid-${Integer.toUnsignedString(dest.name.hashCode(), 16)}-"
            val tmp = File(dest.parentFile, "$prefix${UUID.randomUUID()}.tmp~")
            // Named before the sweep looks at the directory, so a write that starts while
            // another sweeps is never taken for what a dead process left.
            LIVE_TEMPS.add(tmp.absolutePath)
            var owned = false
            try {
                dest.parentFile?.let(::sweepStrayTemps)
                try {
                    Files.newOutputStream(
                        tmp.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS,
                    ).use { output ->
                        owned = true
                        write(output)
                    }
                } catch (e: IOException) {
                    onError?.invoke(e.message?.trim().orEmpty().ifEmpty { e.javaClass.simpleName })
                    return false
                }
                if (!tmp.renameTo(dest)) {
                    onError?.invoke("could not move ${tmp.name} onto ${dest.name}")
                    return false
                }
                owned = false
                return true
            } finally {
                if (owned) tmp.delete()
                LIVE_TEMPS.remove(tmp.absolutePath)
            }
        }
    } finally {
        releaseWriteLock(path, lock)
    }
}


/**
 * Waits for what has been written so far to reach the storage medium.
 *
 * A barrier, not an atomicity guarantee, and that distinction is the whole of
 * what this is for. [writeAtomically] already gives each file all-or-nothing
 * CONTENT through a rename, and that much survives process death: once
 * `write(2)` and `rename(2)` have returned the kernel owns the bytes, so a force
 * stop, a low-memory kill or a phantom-process kill cannot take them back. What
 * none of it survives is power loss or a panic, where the dirty pages die
 * unwritten.
 *
 * That only matters because the completion flag is not symmetric with the
 * payload it certifies. `SharedPreferences.commit()` writes the XML and then
 * fsyncs it, `SharedPreferencesImpl.writeToFile` calling `FileUtils.sync`, which
 * is `getFD().sync()`, so the record saying setup finished is on the medium
 * while the several hundred MiB it vouches for may be nothing but page cache.
 * Calling this first orders the run's writes ahead of the flag.
 *
 * What it does NOT buy, so that nobody reads it for more than it is:
 *
 * - It does not make any single file atomic. That is [writeAtomically]'s rename,
 *   and it is unchanged.
 * - It does not make the flush and the flag one event. Power loss DURING this
 *   call leaves exactly the old ordering, over a window the length of the sync
 *   rather than the length of the run. It narrows; it does not close.
 * - It does nothing for a crash in the middle of extraction. The attempt marker
 *   is committed before the first byte, so a retry still resumes over files this
 *   never saw.
 *
 * `android.system.Os` has no `sync(2)`. Its only three entry points of that
 * family are `fsync`, `fdatasync` and `msync`, checked against `android.jar` at
 * compileSdk 37, whose api-versions.xml lists no `sync` at any level, so a
 * whole-filesystem barrier has to come from toybox, which has provided
 * `/system/bin/sync` since Android 6. The in-process alternative is an `fsync`
 * inside [writeAtomically], which is the same barrier spread over roughly 23,000
 * extracted files at one journal commit each: minutes added to every cold
 * install, to close a window measured in seconds.
 *
 * Best effort by design. A device that will not run it loses the barrier and
 * nothing else, leaving the caller exactly where it stood before this existed,
 * so every failure is logged and none is thrown. The wait is bounded because
 * `sync(2)` blocks on the whole system's dirty set and not merely on ours, and
 * no stream is piped: with both outputs discarded the child cannot fill a buffer
 * nobody is draining and so cannot defeat that bound.
 */
internal fun flushWritesToMedia() {
    val started = System.currentTimeMillis()
    try {
        // /dev/null rather than Redirect.DISCARD, which android.jar declares but
        // the compile bootclasspath does not, so it fails to resolve here.
        val devNull = File("/dev/null")
        val sync = ProcessBuilder("/system/bin/sync")
            .redirectInput(devNull)
            .redirectOutput(devNull)
            .redirectError(devNull)
            .start()
        if (!sync.waitFor(SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            sync.destroyForcibly()
            Logger.w(SYNC_TAG, "sync did not return within ${SYNC_TIMEOUT_SECONDS}s; continuing unflushed")
            return
        }
        // Logged either way, and the exit status is why. A device whose policy
        // refuses the exec, or whose image has no toybox, otherwise loses the
        // barrier in silence and reads from the outside exactly like one that
        // flushed.
        val elapsed = System.currentTimeMillis() - started
        if (sync.exitValue() == 0) {
            Logger.i(SYNC_TAG, "Flushed writes to media in ${elapsed}ms")
        } else {
            Logger.w(SYNC_TAG, "sync exited ${sync.exitValue()} after ${elapsed}ms; continuing unflushed")
        }
    } catch (e: Exception) {
        Logger.w(SYNC_TAG, "Could not flush writes to media: ${e.message}")
    }
}


/** Its own tag, not either caller's: two classes share the one function. */
private const val SYNC_TAG = "FlushWrites"


/**
 * Long enough that a real flush of a cold install's tail is never cut short, and
 * short enough that a stuck one cannot park setup for ever. `sync(2)` waits on
 * every dirty page on the device, including other apps', so the figure is a
 * ceiling on someone else's I/O as much as on ours.
 */
private const val SYNC_TIMEOUT_SECONDS = 30L


/**
 * PEM-encodes one certificate, in the shape the concatenated bundle is made of.
 *
 * The files under the system trust store are already PEM, so this exists only
 * for the user-installed half, which arrives as parsed [Certificate] objects and
 * has to be turned back into text before it can be appended. MIME encoding with
 * a 64-character line length and a bare newline separator gives it the shape
 * every other entry in the file already has, since the system store's files are
 * copied through byte for byte and OpenSSL wrote them at 64 columns. Whether
 * the reader on the far side insists on the breaks is untested here and beside
 * the point: matching the rest of the file costs one encoder argument, and a
 * single unbroken run of base64 in the middle of a bundle is a difference with
 * nothing to gain from it.
 *
 * `java.util.Base64` rather than `android.util.Base64` so the encoding is the
 * same object in a unit test as it is on the device. minSdk is 29, well past
 * the 26 that added it.
 */
internal fun pemOf(cert: Certificate): String =
    "-----BEGIN CERTIFICATE-----\n" +
        Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(cert.encoded) +
        "\n-----END CERTIFICATE-----\n"


/** SHA-256 of [text] as lower-case hex, for the user-CA freshness marker. */
internal fun sha256HexOf(text: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }


/**
 * One monitor per destination path, held only while a write to that path is
 * in flight.
 *
 * Temporary names are unique per invocation, so the destination is the stable
 * key that keeps writers to the same file on one monitor.
 *
 * Counted, and removed at zero, so the table holds an entry per write RUNNING
 * rather than an entry per file ever written. That is what makes a per-path
 * monitor affordable at all: a single map keyed by every one of the 3787
 * destinations an extraction touches would be kept for the life of the process
 * to serialise writes that had already finished. The count is incremented under
 * this same monitor before the caller blocks on the entry, so an entry cannot be
 * dropped while anyone is still waiting on it and two threads on one path always
 * meet the same object.
 *
 * A plain `HashMap` guarded by `synchronized` rather than a concurrent map: the
 * claim and the count have to move together, and the section is two field reads
 * long.
 */
private val WRITE_LOCKS = HashMap<String, DestinationLock>()


/** A monitor for one destination path, plus how many writers still need it. */
private class DestinationLock {
    var holders = 0
}


private fun claimWriteLock(path: String): DestinationLock = synchronized(WRITE_LOCKS) {
    WRITE_LOCKS.getOrPut(path) { DestinationLock() }.also { it.holders++ }
}


private fun releaseWriteLock(path: String, lock: DestinationLock) = synchronized(WRITE_LOCKS) {
    if (--lock.holders == 0) WRITE_LOCKS.remove(path)
}


/**
 * Stable canonical key for [WRITE_LOCKS], resolving existing ancestor symlinks without following
 * a symlink leaf itself (the caller resolves the leaf via [linkTarget] when `throughLink` is true).
 * Walks up past any missing ancestor directories so keys match before and after `mkdirs()`.
 */
private fun lockKey(file: File): String {
    val raw = file.toPath().toAbsolutePath()
    try {
        return raw.toRealPath(LinkOption.NOFOLLOW_LINKS).toString()
    } catch (_: IOException) {
    }
    val missing = ArrayDeque<java.nio.file.Path>()
    var current: java.nio.file.Path? = raw
    while (current != null) {
        val parent = current.parent
        val name = current.fileName
        if (name != null) missing.addFirst(name)
        if (parent == null) break
        try {
            var resolved = parent.toRealPath()
            for (part in missing) {
                resolved = resolved.resolve(part)
            }
            return resolved.normalize().toString()
        } catch (_: IOException) {
            current = parent
        }
    }
    return raw.normalize().toString()
}


/**
 * Runs [block] holding the lock [writeAtomically] holds for [dest], for a change of [dest] made in
 * more than one step that a write of it must not land inside.
 */
internal fun <T> holdingWriteLock(dest: File, throughLink: Boolean = true, block: () -> T): T {
    val target = if (throughLink) linkTarget(dest) ?: dest else dest
    val path = lockKey(target)
    val lock = claimWriteLock(path)
    try {
        return synchronized(lock) { block() }
    } finally {
        releaseWriteLock(path, lock)
    }
}


/**
 * A path as the writers and the sweep both name it: through its links. The app's files are reached as
 * `/data/user/0/<package>/...` by most callers and as `/data/data/<package>/...` by the ones that
 * canonicalise, and the sweep has to see one name for one file.
 */
private fun spelling(file: File): String = try { file.canonicalPath } catch (_: IOException) { file.absolutePath }


/** The temporary files [writeAtomically] is writing right now, so a sweep never takes one for a stray. */
private val LIVE_TEMPS: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()


/**
 * The directories this process has swept for strays, so each is listed once however many files it gets.
 * By the spelling a caller used, which answers "again?" for the thousands of writes of an extraction without
 * a call to the filesystem, and by the canonical one, which makes two spellings of one directory one.
 */
private val SWEPT_RAW: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

private val SWEPT_FOR_STRAYS: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()


/** What [writeAtomically] names its temporary file: the destination's name hashed in hex, a UUID, `.tmp~`. */
private val STRAY_TEMP_NAME =
    Regex("\\.vscodroid-[0-9a-f]{1,8}-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.tmp~")


/**
 * Deletes from [dir] the temporary files that a process which died inside [writeAtomically]
 * left behind.
 *
 * Their names carry a unique id, so no later write of the same file reuses them the way the one
 * fixed name they replaced was, and the largest are the size of the file being written: 92 MiB
 * for the Copilot runtime, about 196 MiB for the libraries of the C and C++ toolchain. Dying
 * inside a write is the only way to leave one, because the writer removes its own on every other
 * exit, so a directory is swept once per process, by the first write into it, by the removal
 * of a toolchain that put files there, or by a check of free room that is about to refuse
 * ([sweepStrayTempsUnder]).
 *
 * Only the exact shape of the writer's name is taken: a file of a person's that merely begins and
 * ends the same way is left alone.
 *
 * @return the bytes it gave back, for a caller that is measuring room.
 */
internal fun sweepStrayTemps(dir: File): Long {
    if (!SWEPT_RAW.add(dir.absolutePath)) return 0L
    if (!SWEPT_FOR_STRAYS.add(spelling(dir))) return 0L
    val names = dir.list { _, name -> STRAY_TEMP_NAME.matches(name) } ?: return 0L
    if (names.isEmpty()) return 0L
    // Read after the listing, and named by the canonical path like the strays: a write puts its
    // temporary file in [LIVE_TEMPS] before it creates it, so one that was listed is already there.
    val live = LIVE_TEMPS.mapTo(HashSet()) { spelling(File(it)) }
    var freed = 0L
    for (name in names) {
        val stray = File(dir, name)
        if (spelling(stray) !in live && Files.isRegularFile(stray.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            val size = stray.length()
            if (stray.delete()) freed += size
        }
    }
    return freed
}


/**
 * [sweepStrayTemps] for [root] and every directory below it, links not entered.
 *
 * The sweep by the first write into a directory comes after the check of free room that an
 * extraction makes, so a device short of room by about what a killed write left was told to free
 * that much more, and the file stayed until it did. A check that is about to refuse calls this
 * first and measures again.
 *
 * @return the bytes it gave back.
 */
internal fun sweepStrayTempsUnder(root: File): Long =
    root.walkTopDown()
        .onEnter { !Files.isSymbolicLink(it.toPath()) }
        .filter { it.isDirectory }
        .sumOf { sweepStrayTemps(it) }


/**
 * Logical bytes already in an extraction tree, excluding symbolic links.
 *
 * This sizes temporary rewrite headroom and identifies an unfinished server tree.
 * Storage credit is measured separately against individual APK files by
 * `FirstRunSetup.extractionSpace`, so retired or unrelated files cannot pay for
 * missing bundled content.
 */
internal fun installedExtractionBytes(root: File): Long =
    root.walkTopDown()
        .onEnter { !Files.isSymbolicLink(it.toPath()) }
        .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
        .sumOf { it.length() }
