package com.vscodroid.setup

import android.system.ErrnoException
import androidx.annotation.StringRes
import com.google.android.play.core.assetpacks.AssetPackException
import com.google.android.play.core.assetpacks.model.AssetPackErrorCode
import com.vscodroid.R
import com.vscodroid.util.Logger
import com.vscodroid.util.StorageManager
import com.vscodroid.webview.redactToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * A staging directory belonging to one download rather than to the class.
 *
 * The path used to be a constant, and the `finally` that cleans up deletes the
 * directory whole. Multiple call sites each construct their own `ToolchainManager`
 * and `ioExecutor` is an instance field, so it serialises nothing between them:
 * rotating the toolchain screen, or cancelling the first-run queue and reaching
 * the picker again, leaves an earlier download still running against the same
 * path. Each one's cleanup then deleted the other's archive and tree, which
 * surfaces as a digest mismatch, the alarm that exists for tampering, raised by
 * a race with ourselves.
 *
 * A process killed mid-download leaves one directory behind, which Android
 * reclaims under storage pressure. The shared path leaked the same way and was
 * merely overwritten by the next attempt rather than reclaimed.
 */
internal fun toolchainTempDir(cacheDir: File, packName: String): File =
    File(cacheDir, "toolchain-download/$packName-${System.nanoTime()}")

/**
 * How far along a Play delivery is, as a percentage.
 *
 * Zero when Play has not said how big the pack is yet, which it does not until
 * the download is under way: dividing by that total unguarded is the one way
 * this arithmetic can fail, and it fails on the first update of every download.
 *
 * At file scope because two callers need the same answer: the state callback,
 * and [ToolchainManager.readPlayDownloads] asking after the fact.
 */
internal fun packDownloadPercent(bytesDownloaded: Long, totalBytes: Long): Int =
    if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt() else 0

/** How long an idle toolchain I/O thread is kept before it is given back. */
internal const val IO_THREAD_KEEPALIVE_MS = 30_000L

/**
 * The single-thread executor each [ToolchainManager] does its file work on.
 *
 * One thread, an unbounded queue and therefore the same serialisation
 * `Executors.newSingleThreadExecutor` gives, with one difference: the thread
 * times out when it has been idle for [IO_THREAD_KEEPALIVE_MS] and is created
 * again on the next submission. A core thread never times out, and that default
 * is what made the lifetime a problem here, because nothing in this class or in
 * any caller ever shuts one down: multiple call sites each construct their own
 * manager, `SplashActivity.onCreate` constructs two of them per launch and
 * submits on both, and the process outlives the screens because the server runs
 * in a foreground service. Every launch therefore parked two more threads that
 * had already finished their work.
 *
 * Shutting the executors down instead would need an owner for each manager, and
 * there is none: `Environment` and `AndroidBridge` build one where they stand.
 * Reclaiming an idle thread costs nothing and needs no owner.
 *
 * At file scope so the configuration can be asserted without reaching into a
 * manager's private field.
 */
internal fun toolchainIoExecutor(
    keepAliveMs: Long = IO_THREAD_KEEPALIVE_MS,
): ThreadPoolExecutor = ThreadPoolExecutor(
    1,
    1,
    keepAliveMs,
    TimeUnit.MILLISECONDS,
    LinkedBlockingQueue(),
) { r -> Thread(r, "toolchain-io").apply { isDaemon = true } }
    .apply { allowCoreThreadTimeOut(true) }

/**
 * How stale a toolchain staging directory has to be before it is swept.
 *
 * A day, and generously so. The only thing this has to be longer than is a
 * download that is still running, and the directory's own timestamp is a weak
 * witness of that: creating the archive and the extraction directory touches it,
 * but writing 56 MB into a file that already exists does not. A day is far
 * beyond any transfer this app performs and costs at most one day of disk.
 */
internal const val ABANDONED_DOWNLOAD_AGE_MS = 24L * 60 * 60 * 1000

/**
 * Whether a staging directory last touched at [lastModifiedMs] belongs to a
 * download nothing is performing any more.
 *
 * Zero is `File.lastModified`'s answer for a timestamp it could not read, and it
 * is treated as "cannot tell" rather than as the epoch: deleting a directory a
 * download is writing into is the failure worth avoiding, and an extra day of
 * disk is the price.
 */
internal fun isAbandonedDownload(lastModifiedMs: Long, nowMs: Long): Boolean =
    lastModifiedMs > 0L && lastModifiedMs < nowMs - ABANDONED_DOWNLOAD_AGE_MS

/**
 * What an HTTP toolchain install needs free, given its sizes and the tree of this
 * build that is already installed.
 *
 * The run has two peaks and this charges for the taller:
 *
 *  - unpacking, holding the archive and the staging tree at once;
 *  - copying, holding the staging tree and what the copy adds to `usr/`. A first
 *    install adds the whole tree, which is why it is charged twice. A reinstall
 *    adds only what the tree on disk does not already hold, and replaces each of
 *    those files by writing a sibling and renaming it over the old one, so one
 *    file's worth of both versions exists at a time: [InstalledTree.largestFile].
 *
 * The archive is deleted after extraction and before the copy, so it is never on
 * disk beside the second tree. A fully credited reinstall puts the second peak
 * below the first, which is why the credit is handed in here instead of being
 * subtracted from a single figure afterwards.
 *
 * So a toolchain that is already installed is charged roughly one tree plus its
 * largest file, where a first install is charged two trees.
 */
internal fun toolchainInstallBytes(
    unpackedBytes: Long,
    downloadBytes: Long,
    installed: InstalledTree = InstalledTree.NONE,
): Long = maxOf(
    downloadBytes + unpackedBytes,
    unpackedBytes * 2 - installed.bytes + installed.largestFile,
) + SPACE_BUFFER

/**
 * What the copy into `usr/` needs free once the incoming tree is on disk and
 * already counted, given what [toolchainCopySpace] measured against the files it
 * will replace.
 *
 * One tree rather than two: the staged copy exists before this is asked. The new
 * allocation is the tree less the bytes of matching files it replaces, never less
 * than the working margin, plus room to write the largest replacement beside its
 * original before the rename. Both delivery paths ask it, so a device is not told
 * yes by one and no by the other about the same toolchain.
 */
internal fun toolchainCopyBytes(unpackedBytes: Long, space: ToolchainCopySpace): Long =
    (packInstallBytes(unpackedBytes) - minOf(unpackedBytes, space.creditedBytes))
        .coerceAtLeast(SPACE_BUFFER) + space.replacementBytes

/**
 * What an installed tree holds that a reinstall can reuse: its bytes, links
 * excluded, and its largest single file.
 */
internal data class InstalledTree(val bytes: Long, val largestFile: Long) {
    companion object {
        val NONE = InstalledTree(0L, 0L)
    }
}

/**
 * Measures the tree under [root] that a reinstall of the build now downloading
 * would write over, or [InstalledTree.NONE].
 *
 * Clamped at [recordedBytes], because a directory can hold more than the pack
 * writes back, and a credit for those bytes is space the copy never returns. A
 * root that resolves outside [within] credits nothing: `..` escapes
 * `File(base, relative)` while an absolute string is re-nested under it, and a
 * credit is the first decision taken on that string. Measuring the whole app
 * data directory would clamp to the pack's own size and leave the gate asking for
 * nothing but the buffer. Links are neither followed nor counted, as in
 * `StorageManager.dirSize`.
 */
internal fun installedTreeSpace(
    root: File?,
    within: File,
    recordedBytes: Long,
    measure: (File) -> InstalledTree = ::measureInstalledTree,
): InstalledTree {
    if (root == null) return InstalledTree.NONE
    val base = runCatching { within.canonicalFile }.getOrNull() ?: return InstalledTree.NONE
    val resolved = runCatching { root.canonicalFile }.getOrNull() ?: return InstalledTree.NONE
    if (!generateSequence(resolved) { it.parentFile }.any { it == base }) return InstalledTree.NONE
    val found = measure(resolved)
    val bytes = minOf(recordedBytes, found.bytes).coerceAtLeast(0)
    return InstalledTree(bytes, minOf(found.largestFile, bytes))
}

/** The bytes and the largest file under [dir], without following or counting links. */
internal fun measureInstalledTree(dir: File): InstalledTree {
    if (!dir.exists()) return InstalledTree.NONE
    var bytes = 0L
    var largest = 0L
    val stack = ArrayDeque<File>()
    stack.addLast(dir)
    while (stack.isNotEmpty()) {
        val f = stack.removeLast()
        if (Files.isSymbolicLink(f.toPath())) continue
        if (f.isFile) {
            val length = f.length()
            bytes += length
            largest = maxOf(largest, length)
        } else if (f.isDirectory) {
            f.listFiles()?.forEach { stack.addLast(it) }
        }
    }
    return InstalledTree(bytes, largest)
}

/**
 * The unpacked size recorded for [packName], or null when none is.
 *
 * Null rather than zero, and the distinction is the whole reason this exists.
 * `ToolchainRegistry.find` answers null for a retired pack, by design: that
 * pass-through is what keeps uninstalling one working after its row is dropped.
 * A caller that spells the lookup as `find(...)?.estimatedSize ?: 0L` therefore
 * turns "I do not know" into "it needs nothing", and a space gate built on that
 * reserves the bare buffer for a tree of any size. Zero is a real answer here
 * only for a pack that genuinely occupies nothing, which none do.
 *
 * [RETIRED_TOOLCHAINS] is consulted second because the packs most likely to be
 * on a device right now are the ones installed before a withdrawal, and the
 * registry is exactly where they are not.
 * The prefix is stripped here rather than through `toolchainShortName`, which
 * resolves through the registry and returns a retired name unchanged.
 */
internal fun packUnpackedBytes(packName: String): Long? =
    ToolchainRegistry.find(packName)?.estimatedSize
        ?: RETIRED_TOOLCHAINS[packName.removePrefix("toolchain_")]

/**
 * What a Play Asset Delivery install needs free, given its unpacked size.
 *
 * One tree rather than two, because by the time this is asked the first copy is
 * already written and already counted. The figure is right; the reason given for
 * it was not, and the wrong reason is worth naming because a later change rested
 * on it.
 *
 * Play does NOT write the pack outside `filesDir`. Verified from asset-delivery
 * 2.2.2's bytecode: the delivered tree sits under `filesDir/assetpacks`, on the
 * same filesystem `StatFs(context.filesDir)` measures, and it is already occupying
 * space when the caller takes that reading. So the only NEW allocation the install
 * makes is the copy into `usr/`, which is what this charges for; peak usage is two
 * trees, and `removePack` frees Play's afterwards.
 */
internal fun packInstallBytes(unpackedBytes: Long): Long =
    unpackedBytes + SPACE_BUFFER

/**
 * Copies a tree, refusing rather than shrugging when a directory cannot be read.
 *
 * `listFiles` answers null both for a directory that is empty of readable
 * entries and for one it could not enumerate at all, and this used to adopt the
 * benign reading with a bare `return`. The caller then wrote the install record
 * and reported the toolchain COMPLETED: a subtree that was never copied, symlinks
 * pointing at files that are not there, a green card, and a command that does not
 * run.
 *
 * At file scope with the lister injected so the refusing branch can be driven
 * from a JVM test, which is the only way to reach it.
 *
 * Each entry is checked with [pathInTheWay] where it is written, as well as by
 * [firstPathInTheWay] before the copy starts, so an entry that comes into the way
 * in between is still refused as what it is rather than as a failed write.
 *
 * @param list how children are enumerated, defaulting to the real filesystem
 * @throws PathOccupied if an entry of the wrong kind stands where the tree writes
 * @throws IOException if a directory exists but cannot be listed, or a write fails
 */
internal fun copyDirectoryTree(
    src: File,
    dest: File,
    list: (File) -> Array<File>? = File::listFiles,
) {
    pathInTheWay(src, dest)?.let { throw it }
    if (src.isDirectory) {
        dest.mkdirs()
        val children = list(src)
            ?: throw IOException(
                "Cannot list ${src.absolutePath}; the toolchain tree is incomplete"
            )
        for (child in children) {
            copyDirectoryTree(child, File(dest, child.name), list)
        }
    } else {
        dest.parentFile?.mkdirs()
        var cause: String? = null
        val copied = writeAtomically(dest, onError = { cause = it }) { output ->
            src.inputStream().use { it.copyTo(output) }
        }
        if (!copied) {
            throw IOException(cause ?: "Could not replace ${dest.name}")
        }
    }
}

/**
 * An entry in `usr/` of the wrong kind for what a pack writes there: a link where the pack writes into a folder (it
 * would write wherever the link points), a file where it needs a folder, or a folder where it writes a file. Freeing
 * space changes none of these, so it is not a lack of storage, and the install reports it as INTERNAL with the path in
 * the log. A link where the pack writes a FILE is not one: the write replaces the link, as it replaces a file.
 */
internal class PathOccupied(path: File, why: String) : IOException("$why: ${path.absolutePath}")

/** Why [dest] cannot take [src] as the copy writes it, or null when it can, decided before anything is made there. */
internal fun pathInTheWay(src: File, dest: File): PathOccupied? {
    val path = dest.toPath()
    return when {
        !src.isDirectory -> if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) null
            else PathOccupied(dest, "A folder holds the name of a file the pack writes")
        Files.isSymbolicLink(path) -> PathOccupied(dest, "A link stands where the pack writes into a folder")
        Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ->
            PathOccupied(dest, "A file holds the name of a folder the pack writes into")
        else -> null
    }
}

/**
 * The first entry under [dest] that [copyDirectoryTree] would refuse with [PathOccupied], or null. Asked before the
 * install writes its marker or a byte of the copy, so a pack that cannot finish for this reason changes nothing.
 * Only folders that already exist are entered, since nothing below a missing one can be in the way.
 */
internal fun firstPathInTheWay(src: File, dest: File): PathOccupied? {
    pathInTheWay(src, dest)?.let { return it }
    if (!src.isDirectory || !Files.isDirectory(dest.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
    return src.listFiles()?.firstNotNullOfOrNull { firstPathInTheWay(it, File(dest, it.name)) }
}

internal data class ToolchainCopySpace(val creditedBytes: Long, val replacementBytes: Long)

/** Credits only matching files, retaining room for one atomic replacement. */
internal fun toolchainCopySpace(src: File, dest: File): ToolchainCopySpace {
    var credit = 0L
    var replacement = 0L
    if (Files.isSymbolicLink(dest.toPath())) return ToolchainCopySpace(0, 0)
    val base = dest.canonicalFile
    src.walkTopDown().forEach { file ->
        if (!file.isFile) return@forEach
        val relative = file.relativeTo(src).path
        val target = File(dest, relative).absoluteFile
        if (!target.isFile || Files.isSymbolicLink(target.toPath()) ||
            target.parentFile.canonicalFile != File(base, relative).parentFile
        ) return@forEach
        val reusable = minOf(file.length(), target.length())
        credit += reusable
        replacement = maxOf(replacement, reusable)
    }
    return ToolchainCopySpace(credit, replacement)
}

/**
 * Gives the execute bit to every ELF object under [root], returning how many
 * needed it.
 *
 * At file scope and taking its symlink predicate as a parameter so a test can
 * reach it. [isSymlink] uses `Os.lstat`, which cannot run in a JVM unit test;
 * injecting the predicate lets the skip-links behaviour be asserted rather than
 * assumed while production passes [isSymlink].
 *
 * Links are stepped over rather than followed: a toolchain's `usr/lib` is shared
 * with the base install, so a link there can point at a file this pass has no
 * business changing the permissions of.
 *
 * Only ELF objects. Scripts are deliberately excluded because they are wrapped in
 * shell functions that route them through their interpreter.
 */
internal fun markExecutablesIn(root: File, isLink: (File) -> Boolean = ::isSymlink): Int {
    var fixed = 0
    root.walkTopDown()
        .onEnter { !isLink(it) }
        .forEach { file ->
            if (!file.isFile || isLink(file)) return@forEach
            if (!isElfFile(file)) return@forEach
            if (file.canExecute()) return@forEach
            if (file.setExecutable(true, true)) fixed++
        }
    return fixed
}

/**
 * Whether [name] is safe to write into `toolchain-env.sh` as a shell function.
 *
 * `.bashrc` sources that file unconditionally, and one unusable name costs the
 * rest of the file. The boundary is measured against bash: `( ) < > = [ $ \ " ' ` `
 * and whitespace make the definition a parse error, and `; | &` split the line.
 */
internal fun isShellFunctionName(name: String): Boolean {
    if (name.isEmpty() || name.startsWith("#") || name in SHELL_RESERVED_WORDS) return false
    if (name.all { it in '0'..'9' }) return false
    return name.none { it in SHELL_UNSAFE || it.isWhitespace() || it.isISOControl() }
}

/** Bash reserved words that cannot appear unquoted as a `name() { ...; }` function identifier. */
private val SHELL_RESERVED_WORDS = setOf(
    "if", "then", "else", "elif", "fi",
    "case", "esac", "for", "select", "while", "until", "do", "done", "in",
    "function", "time", "coproc",
    "{", "}", "!", "[[", "]]",
)

/**
 * The characters measured to break a function definition or to silently define one
 * under another name.
 */
private const val SHELL_UNSAFE = "()<>=[\$\\\"'`;|&"

/** Whether [file] begins with the four bytes every ELF object starts with. */
internal fun isElfFile(file: File): Boolean = try {
    file.inputStream().use { input ->
        val header = ByteArray(4)
        input.read(header) == 4 && isElfHeader(header)
    }
} catch (e: Exception) {
    false
}

/**
 * Whether these opening bytes are an ELF object's.
 *
 * The same four bytes the packaging gates read. Separated so the repair pass can
 * be checked against real files rather than a mock that would only agree with
 * the implementation it was written from.
 */
internal fun isElfHeader(header: ByteArray): Boolean =
    header.size >= 4 &&
        header[0] == 0x7F.toByte() &&
        header[1] == 'E'.code.toByte() &&
        header[2] == 'L'.code.toByte() &&
        header[3] == 'F'.code.toByte()

/**
 * Whether a transfer that ended may be treated as the whole file.
 *
 * @param declaredBytes the `Content-Length` the server sent, or a value <= 0
 *   when it sent none. Absence has to be accepted rather than treated as zero:
 *   the header is optional and a chunked response omits it.
 * @param receivedBytes what the read loop actually counted
 */
internal fun isCompleteTransfer(declaredBytes: Long, receivedBytes: Long): Boolean =
    declaredBytes <= 0L || receivedBytes == declaredBytes

internal const val MAX_EXTRACT_BYTES = 2L * 1024 * 1024 * 1024 // 2 GiB
internal const val MAX_EXTRACT_ENTRIES = 50_000

/**
 * Extracts [zipFile] into [destDir] with zip-slip confinement, cancellation checks per entry and
 * per read chunk, and explicit upper bounds on total extracted bytes and entry count.
 */
@Throws(IOException::class, SecurityException::class)
internal fun extractToolchainZip(
    zipFile: File,
    destDir: File,
    appRoot: File? = destDir.parentFile,
    isCancelled: () -> Boolean = { false },
    maxBytes: Long = MAX_EXTRACT_BYTES,
    maxEntries: Int = MAX_EXTRACT_ENTRIES,
) {
    if (isCancelled()) throw IOException("Extraction cancelled")
    val canonicalDest = destDir.canonicalPath
    var entryCount = 0
    var totalBytes = 0L
    val buffer = ByteArray(8192)
    val created = ArrayDeque<File>()

    try {
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (isCancelled()) throw IOException("Extraction cancelled")
                entryCount++
                if (entryCount > maxEntries) {
                    throw SecurityException("Zip entry limit exceeded ($maxEntries)")
                }
                val outFile = File(destDir, entry.name)
                val canonicalOut = outFile.canonicalPath

                if (entry.isDirectory) {
                    if (!canonicalOut.startsWith(canonicalDest + File.separator) && canonicalOut != canonicalDest) {
                        throw SecurityException("Zip slip detected: ${entry.name}")
                    }
                    if (canonicalOut != canonicalDest) {
                        outFile.mkdirs()
                        created.addLast(outFile)
                    }
                } else {
                    if (!canonicalOut.startsWith(canonicalDest + File.separator)) {
                        throw SecurityException("Zip slip detected: ${entry.name}")
                    }
                    outFile.parentFile?.mkdirs()
                    if (Files.isSymbolicLink(outFile.toPath())) {
                        throw SecurityException("Refusing to write zip entry through symlink: ${entry.name}")
                    }
                    created.addLast(outFile)
                    FileOutputStream(outFile).use { out ->
                        while (true) {
                            if (isCancelled()) throw IOException("Extraction cancelled")
                            val read = zis.read(buffer)
                            if (read < 0) break
                            totalBytes += read
                            if (totalBytes > maxBytes) {
                                throw SecurityException("Zip extracted size limit exceeded ($maxBytes bytes)")
                            }
                            out.write(buffer, 0, read)
                        }
                    }
                }

                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    } catch (e: Throwable) {
        StorageManager.deleteRecursive(destDir, appRoot)
        while (created.isNotEmpty()) {
            val path = created.removeLast()
            if (!Files.isSymbolicLink(path.toPath())) {
                path.delete()
            }
        }
        throw e
    }
}

/** GitHub release and release-asset hosts allowed for HTTPS toolchain download redirects. */
internal fun isAllowedToolchainHost(host: String): Boolean {
    val normalized = host.lowercase(Locale.ROOT).removeSuffix(".")
    if (normalized.isEmpty() || normalized.split('.').any { it.isEmpty() }) return false
    return normalized == "github.com" ||
        (normalized.endsWith(".githubusercontent.com") && normalized.length > ".githubusercontent.com".length)
}

/**
 * Where a redirect from [currentUrl] points, refusing one that would drop TLS
 * or leave the allowed GitHub release hosts.
 *
 * @throws IOException when following the hop would leave TLS behind or reach a disallowed host
 */
@Throws(IOException::class)
internal fun nextRedirectUrl(currentUrl: String, location: String): String {
    if (location.isBlank()) {
        throw IOException("Refusing a redirect with a blank Location header from $currentUrl")
    }
    val current = URL(currentUrl)
    val next = URL(current, location)
    if (!next.protocol.equals("https", ignoreCase = true) && !next.protocol.equals("http", ignoreCase = true)) {
        throw IOException("Refusing a redirect from $currentUrl to unsupported scheme ${next.protocol}")
    }
    if (current.protocol.equals("https", ignoreCase = true)) {
        if (!next.protocol.equals("https", ignoreCase = true)) {
            throw IOException("Refusing a redirect from $currentUrl to cleartext $next")
        }
        if (next.port != -1 && next.port != 443) {
            throw IOException("Refusing a redirect from $currentUrl to non-standard port ${next.port}")
        }
        if (next.userInfo != null || !isAllowedToolchainHost(next.host)) {
            throw IOException("Refusing a redirect from $currentUrl to disallowed host ${next.host}")
        }
    }
    return next.toString()
}

/**
 * Why an install failed, in the terms the person looking at the screen can act in.
 *
 * Grouped by what to do rather than by where the exception came from: a full disk
 * and a failed state write are both [STORAGE] because both are answered by freeing
 * space, and a truncated archive and a manifest missing its name are both [CORRUPT]
 * because both are answered by retrying.
 */
// `@param:` rather than a bare `@StringRes`, which Kotlin warns will start
// applying to the backing field as well. Naming the target pins today's meaning
// instead of letting a compiler upgrade choose a different one.
enum class ToolchainFailure(@param:StringRes val message: Int) {
    NETWORK(R.string.toolchain_failed_network),
    STORAGE(R.string.toolchain_failed_storage),
    NOT_PUBLISHED(R.string.toolchain_failed_not_published),
    DIGEST(R.string.toolchain_failed_digest),
    CORRUPT(R.string.toolchain_failed_corrupt),
    // Reached from both delivery paths, so its message names what Play will not
    // do rather than which build is running: a sideloaded install with no ZIP for
    // this pack, and a Play install Play does not recognise as one of its own.
    PLAY_REQUIRED(R.string.toolchain_failed_play),
    INTERNAL(R.string.toolchain_failed_internal),
    // A Remove that did not happen. Reported with UNKNOWN rather than FAILED, which a card draws as a failed install
    // with a Retry that installs ([ToolchainManager.failRemoval]).
    REMOVAL(R.string.toolchain_remove_failed),
}

/**
 * What a link the install could not make reports: [ToolchainFailure.STORAGE] only for a disk or a quota
 * with no room for another entry, ENOSPC (28) or EDQUOT (122), and [ToolchainFailure.INTERNAL] for anything else.
 */
internal fun linkFailure(e: Exception): ToolchainFailure = when ((e as? ErrnoException)?.errno) {
    28, 122 -> ToolchainFailure.STORAGE
    else -> ToolchainFailure.INTERNAL
}

/**
 * Why a Play fetch was refused before any state was ever delivered.
 */
internal fun playFetchFailure(e: Exception): ToolchainFailure =
    (e as? AssetPackException)?.let { toolchainFailureFor(it.errorCode) }
        ?: ToolchainFailure.INTERNAL

/**
 * Play's error code for a failed asset pack, in the terms [ToolchainFailure] speaks.
 */
internal fun toolchainFailureFor(errorCode: Int): ToolchainFailure = when (errorCode) {
    AssetPackErrorCode.NETWORK_ERROR -> ToolchainFailure.NETWORK
    AssetPackErrorCode.INSUFFICIENT_STORAGE -> ToolchainFailure.STORAGE
    AssetPackErrorCode.PACK_UNAVAILABLE -> ToolchainFailure.NOT_PUBLISHED
    AssetPackErrorCode.APP_NOT_OWNED,
    AssetPackErrorCode.UNRECOGNIZED_INSTALLATION -> ToolchainFailure.PLAY_REQUIRED
    else -> ToolchainFailure.INTERNAL
}

/** The digest manifest's filename, as `release.yml` writes it beside the ZIPs. */
private const val MANIFEST_NAME = "toolchains.sha256"

/** The detached ECDSA P-256 signature for [MANIFEST_NAME], written beside it in `release.yml`. */
private const val MANIFEST_SIGNATURE_NAME = "toolchains.sha256.sig"

/**
 * Where to find the digest manifest for a toolchain ZIP: beside it.
 *
 * Derived from the ZIP's own URL rather than written down separately, which
 * removes a second constant that could drift from the first.
 */
internal fun manifestUrlFor(zipUrl: String): String =
    zipUrl.substringBeforeLast('/') + "/" + MANIFEST_NAME

/**
 * Where to find the detached ECDSA P-256 signature for a toolchain digest manifest: beside it.
 */
internal fun manifestSignatureUrlFor(zipUrl: String): String =
    zipUrl.substringBeforeLast('/') + "/" + MANIFEST_SIGNATURE_NAME

/**
 * The release-level `latest` URL an asset URL resolves through, or null when
 * the URL is not of that shape.
 *
 * `https://host/o/r/releases/latest/download/x.zip` gives
 * `https://host/o/r/releases/latest`.
 */
internal fun latestReleaseUrlFor(assetUrl: String): String? {
    val i = assetUrl.indexOf(LATEST_DOWNLOAD)
    return if (i < 0) null else assetUrl.substring(0, i) + "/releases/latest"
}

/**
 * The tag named by a `releases/latest` redirect, or null.
 *
 * `https://host/o/r/releases/tag/v1.2.3` gives `v1.2.3`.
 */
internal fun releaseTagFromLocation(location: String): String? {
    val i = location.indexOf(RELEASES_TAG)
    if (i < 0) return null
    val tag = location.substring(i + RELEASES_TAG.length)
        .substringBefore('?')
        .substringBefore('#')
        .trim('/')
    return if (tag.isNotEmpty() && TAG_CHARS.matches(tag)) tag else null
}

/**
 * The release tag a build of [versionName] belongs to, or null.
 *
 * `1.1.0` gives `v1.1.0`, matching the tags `release.yml` publishes.
 * `-debug` is stripped because it is a `versionNameSuffix` on the debug build type.
 */
internal fun appReleaseTag(versionName: String): String? {
    val version = versionName.removeSuffix("-debug")
    return if (version.isNotEmpty() && TAG_CHARS.matches(version)) "v$version" else null
}

/**
 * [assetUrl] with `latest` replaced by a concrete [tag], or null.
 *
 * `.../releases/latest/download/x.zip` and `v1.2.3` give
 * `.../releases/download/v1.2.3/x.zip`.
 */
internal fun pinnedAssetUrl(assetUrl: String, tag: String): String? {
    val i = assetUrl.indexOf(LATEST_DOWNLOAD)
    if (i < 0) return null
    val asset = assetUrl.substring(i + LATEST_DOWNLOAD.length)
    if (asset.isEmpty() || asset.contains('/')) return null
    if (!TAG_CHARS.matches(tag)) return null
    return assetUrl.substring(0, i) + "/releases/download/" + tag + "/" + asset
}

private const val LATEST_DOWNLOAD = "/releases/latest/download/"
private const val RELEASES_TAG = "/releases/tag/"
private val TAG_CHARS = Regex("""[A-Za-z0-9._-]+""")

/**
 * The digest a `sha256sum` manifest publishes for [fileName], or null when it
 * publishes none it can be trusted to mean.
 *
 * Every doubt resolves to null: lines without a 64-hex digest are skipped,
 * two lines naming this file with different digests return null, and absence
 * returns null. Names are compared as bare filenames with any leading `*` stripped.
 */
internal fun digestFromManifest(manifest: String, fileName: String): String? {
    var found: String? = null
    for (raw in manifest.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue

        val parts = line.split(WHITESPACE, limit = 2)
        if (parts.size != 2) continue

        val digest = parts[0].lowercase()
        if (!SHA256_HEX.matches(digest)) continue

        val name = parts[1].trim().removePrefix("*").substringAfterLast('/')
        if (name != fileName) continue

        if (found != null && found != digest) return null
        found = digest
    }
    return found
}

private val WHITESPACE = Regex("""\s+""")
private val SHA256_HEX = Regex("""^[0-9a-f]{64}$""")

/**
 * The SHA-256 of [file], lowercase hex. Streamed in 64 KB chunks.
 */
internal fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    file.inputStream().buffered().use { input ->
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Whether `export [key]="[value]"` is a line `toolchain-env.sh` can carry.
 */
internal fun isShellSafeEnvEntry(key: String, value: String): Boolean =
    key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) &&
        value.none { it == '"' || it == '`' || it == '\\' || it == '\n' || it == '\r' } && "\$(" !in value

/** Whether [dir], a manifest's `pathDirs` entry, can follow `$PREFIX/../` in the same file. */
internal fun isShellSafePathDir(dir: String): Boolean =
    dir.isNotEmpty() && !dir.startsWith("/") &&
        dir.split('/').none { it == ".." || it.isEmpty() } &&
        dir.none { it == '"' || it == '`' || it == '\\' || it == '\n' || it == '\r' || it == '$' }

/**
 * Whether [relative] may name a path a toolchain owns, in a manifest's
 * `ownedPaths`: under `usr/`, at least three components deep, with no `.` or
 * `..` component and no empty one.
 */
internal fun isOwnedPathName(relative: String): Boolean {
    if (!relative.startsWith("usr/")) return false
    val parts = relative.split('/')
    if (parts.size < 3) return false
    return parts.none { it.isEmpty() || it == "." || it == ".." }
}

/**
 * The name a toolchain is persisted under, from either form callers use.
 *
 * Resolves through [ToolchainRegistry.find] and returns unrecognized names
 * unchanged so retired toolchains can still be uninstalled.
 */
internal fun toolchainShortName(nameOrPack: String): String =
    ToolchainRegistry.find(nameOrPack)?.packName?.removePrefix("toolchain_") ?: nameOrPack

/**
 * Names the manifest `libs` entries an uninstall may delete from the shared
 * `usr/lib`, given what the base APK ships there.
 *
 * @param libs the manifest's `libs` entries
 * @param baseShipped names the base APK ships in `usr/lib`, or null when the
 *   listing could not be read, in which case nothing is safe to remove
 */
internal fun toolchainLibsSafeToRemove(libs: List<String>, baseShipped: Set<String>?): List<String> {
    if (baseShipped == null) return emptyList()
    return libs.filter { it !in baseShipped }
}

/**
 * Every `usr/lib` entry the install record names for a toolchain other than
 * [exceptName].
 */
internal fun otherToolchainLibs(state: JSONArray, exceptName: String): Set<String> {
    val shared = mutableSetOf<String>()
    for (i in 0 until state.length()) {
        val entry = state.optJSONObject(i) ?: continue
        if (entry.optString("name") == exceptName) continue
        val libs = entry.optJSONArray("libs") ?: continue
        for (j in 0 until libs.length()) shared.add(libs.getString(j))
    }
    return shared
}

/** Every `usr/lib` symlink key declared by another installed toolchain. */
internal fun otherToolchainLibSymlinks(state: JSONArray, exceptName: String): Set<String> {
    val shared = mutableSetOf<String>()
    for (i in 0 until state.length()) {
        val entry = state.optJSONObject(i) ?: continue
        if (entry.optString("name") == exceptName) continue
        val links = entry.optJSONObject("libSymlinks") ?: continue
        links.keys().forEach { shared.add(it) }
    }
    return shared
}

/** Every `usr/bin` symlink key declared by another installed toolchain. */
internal fun otherToolchainSymlinks(state: JSONArray, exceptName: String): Set<String> {
    val shared = mutableSetOf<String>()
    for (i in 0 until state.length()) {
        val entry = state.optJSONObject(i) ?: continue
        if (entry.optString("name") == exceptName) continue
        val links = entry.optJSONObject("symlinks") ?: continue
        links.keys().forEach { shared.add(it) }
    }
    return shared
}

/** Every manifest binary path declared by another installed toolchain. */
internal fun otherToolchainBinaries(state: JSONArray, exceptName: String): Set<String> {
    val shared = mutableSetOf<String>()
    for (i in 0 until state.length()) {
        val entry = state.optJSONObject(i) ?: continue
        if (entry.optString("name") == exceptName) continue
        val binaries = entry.optJSONArray("binaries") ?: continue
        for (j in 0 until binaries.length()) shared.add(binaries.getString(j))
    }
    return shared
}

/** Whether [file] exists on disk or is a symlink (including a dangling symlink). */
internal fun pathExistsNoFollow(file: File): Boolean =
    file.exists() || Files.isSymbolicLink(file.toPath())

/** Counts entries under [root] without following symlinks. */
internal fun countEntriesNoFollow(root: File): Int {
    if (!pathExistsNoFollow(root)) return 0
    if (Files.isSymbolicLink(root.toPath()) || !root.isDirectory) return 1
    var count = 0
    val stack = ArrayDeque<File>()
    stack.addLast(root)
    while (stack.isNotEmpty()) {
        val f = stack.removeLast()
        count++
        if (!Files.isSymbolicLink(f.toPath()) && f.isDirectory) {
            f.listFiles()?.forEach { stack.addLast(it) }
        }
    }
    return count
}

/** Keeps a selected leaf link unlinkable, but refuses redirected parents under [filesDir]. */
internal fun confinedToolchainPath(filesDir: File, relative: String): File? = runCatching {
    val base = filesDir.canonicalFile
    val file = File(base, relative).absoluteFile
    file.takeIf {
        it == it.normalize() && it.path.startsWith(base.path + File.separator) &&
            it.parentFile.canonicalFile == it.parentFile
    }
}.getOrNull()

/** Whether [relative] is lexically a clean path inside `usr/` with no traversal or leading slash. */
internal fun isLexicalUsrPath(relative: String): Boolean {
    if (relative.isEmpty() || relative.startsWith("/")) return false
    val parts = relative.split('/')
    return parts.size >= 2 && parts[0] == "usr" && parts.none { it.isEmpty() || it == "." || it == ".." }
}

/** Whether [name] is a single safe entry name with no path separators or traversal segments. */
internal fun isSinglePathSegment(name: String): Boolean =
    name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name

/** Resolves [relative] strictly under `filesDir/usr/`, refusing traversal and symlinked ancestors. */
internal fun confinedUsrPath(filesDir: File, relative: String): File? =
    if (isLexicalUsrPath(relative)) confinedToolchainPath(filesDir, relative) else null

/** Confirms a link target stays under `filesDir/usr/`, including when the target itself is a link. */
internal fun isConfinedUsrTarget(filesDir: File, relative: String): Boolean {
    val resolved = confinedUsrPath(filesDir, relative) ?: return false
    val usrRoot = runCatching { File(filesDir.canonicalFile, "usr") }.getOrNull() ?: return false
    if (Files.isSymbolicLink(resolved.toPath()) || resolved.exists()) {
        val canonical = runCatching { resolved.canonicalFile }.getOrNull() ?: return false
        if (!canonical.path.startsWith(usrRoot.path + File.separator)) return false
    }
    return true
}

/**
 * The paths a manifest's `ownedPaths` names that [isOwnedPathName] accepts,
 * as files under [filesDir].
 */
internal fun ownedPaths(filesDir: File, manifest: JSONObject, tag: String): List<File> {
    val names = manifest.optJSONArray("ownedPaths") ?: return emptyList()
    val out = mutableListOf<File>()
    for (i in 0 until names.length()) {
        val relative = names.optString(i, "")
        if (isOwnedPathName(relative)) {
            out.add(File(filesDir, relative))
        } else {
            Logger.w(tag, "Ignoring an owned path that is not one: ${redactToken(relative)}")
        }
    }
    return out
}

/**
 * A manifest value with its placeholders resolved to this install's paths.
 */
internal fun expandToolchainValue(filesDir: String, homeDir: String, value: String): String =
    value.replace("\$FILESDIR", filesDir).replace("\$HOME", homeDir)
