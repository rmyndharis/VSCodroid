package com.vscodroid.setup

import android.system.Os
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.vscodroid.util.Logger
import com.vscodroid.util.StorageManager
import com.vscodroid.webview.redactToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/**
 * Handles toolchain removal, partial-copy reclamation, superseded install-root cleanup,
 * retired toolchain removal, and pending Play pack-release marker bookkeeping.
 */
internal class ToolchainUninstaller(private val manager: ToolchainManager) {

    enum class RemovalOutcome { UNCHANGED, PARTIAL, COMPLETE }

    private data class OrphanAnalysis(
        val orphan: JSONObject,
        val hasUnreclaimableOverlap: Boolean,
        val overlapsInstalledExecutable: Boolean,
    )

    fun packReleaseMarker(packName: String): File? {
        if (!isSinglePathSegment(packName)) return null
        return File(manager.packReleaseMarkerDir, packName)
    }

    fun markPackReleasePending(packName: String): Long = synchronized(ToolchainManager.packOperationLock) {
        val generation = ToolchainManager.nextPackReleaseGeneration.incrementAndGet()
        ToolchainManager.packReleaseGenerations[packName] = generation
        val marker = packReleaseMarker(packName) ?: run {
            Logger.w(
                manager.tag,
                "Refusing to write pack release marker for invalid pack name: ${redactToken(packName)}",
            )
            return@synchronized generation
        }
        manager.packReleaseMarkerDir.mkdirs()
        if (!writeAtomically(marker) { it.write(packName.toByteArray()) }) {
            Logger.w(manager.tag, "Could not persist pending pack release marker for $packName")
        }
        generation
    }

    fun clearPackReleaseMarker(packName: String, dropPendingRelease: Boolean = false) {
        synchronized(ToolchainManager.packOperationLock) {
            ToolchainManager.packReleaseGenerations[packName] =
                ToolchainManager.nextPackReleaseGeneration.incrementAndGet()
            if (dropPendingRelease) ToolchainManager.pendingPackReleases.remove(packName)
            val marker = packReleaseMarker(packName) ?: return
            if (marker.exists() || Files.isSymbolicLink(marker.toPath())) {
                marker.delete()
            }
        }
    }

    /** Clears a release marker when Play reported no delivered pack and no newer event intervened. */
    fun clearPackReleaseMarkerIfUnchanged(packName: String, expectedGeneration: Long?) {
        synchronized(ToolchainManager.packOperationLock) {
            if (packName in ToolchainManager.installsInFlight || packName in ToolchainManager.packRemovalsInFlight) return
            if (ToolchainManager.packReleaseGenerations[packName] != expectedGeneration) return
            val marker = packReleaseMarker(packName) ?: return
            if (marker.exists() || Files.isSymbolicLink(marker.toPath())) {
                marker.delete()
            }
        }
    }

    /**
     * Atomically checks for a pending-release marker and, if present, claims the pack for removal
     * under [ToolchainManager.packOperationLock]. Returns true when reconciliation must not install the delivery.
     */
    fun releasePackIfPending(packName: String): Boolean {
        val generationToRelease: Long? = synchronized(ToolchainManager.packOperationLock) {
            val marker = packReleaseMarker(packName)
            val pending = marker != null && (marker.exists() || Files.isSymbolicLink(marker.toPath()))
            if (!pending) return false
            if (packName in ToolchainManager.installsInFlight || packName in ToolchainManager.packRemovalsInFlight) {
                return true
            }
            ToolchainManager.packRemovalsInFlight.add(packName)
            ToolchainManager.packReleaseGenerations[packName]
                ?: ToolchainManager.nextPackReleaseGeneration.incrementAndGet().also {
                    ToolchainManager.packReleaseGenerations[packName] = it
                }
        }
        if (generationToRelease != null) {
            Logger.i(manager.tag, "Reclaiming a delivered pack for an uninstalled toolchain: $packName")
            manager.startRemovePackInternal(packName, generationToRelease)
        }
        return true
    }

    /**
     * Accepts either name form (`ruby` or `toolchain_ruby`), takes the process-wide pack claim,
     * and removes the toolchain under [ToolchainManager.stateLock].
     */
    fun uninstallSync(nameOrPack: String) {
        val name = toolchainShortName(nameOrPack)
        val packName = "toolchain_$name"
        val claimed = synchronized(ToolchainManager.packOperationLock) {
            !ToolchainManager.httpDownloads.containsKey(packName) &&
                manager.claimPackInternal(packName, readsDeliveredPack = false)
        }
        if (!claimed) {
            Logger.i(manager.tag, "An install of $packName is running; not removing it underneath")
            manager.reportState(packName, AssetPackStatus.UNKNOWN, 0)
            return
        }
        try {
            synchronized(ToolchainManager.stateLock) { uninstallLocked(name) }
        } finally {
            manager.releasePackClaimInternal(packName)
        }
    }

    /**
     * Caller must hold [ToolchainManager.stateLock].
     */
    fun uninstallLocked(name: String) {
        val state = manager.readableStateFile() ?: run {
            Logger.e(manager.tag, "${manager.stateFile.path} exists but cannot be read; leaving it as it is")
            manager.failRemovalState("toolchain_$name")
            return
        }
        var manifestObj: JSONObject? = null
        var idx = -1

        for (i in 0 until state.length()) {
            val obj = state.optJSONObject(i) ?: continue
            if (obj.optString("name") == name) {
                manifestObj = obj
                idx = i
                break
            }
        }

        val packName = "toolchain_$name"
        if (manifestObj == null) {
            Logger.w(manager.tag, "Toolchain ${redactToken(name)} not found in state")
            manager.reportState(packName, AssetPackStatus.NOT_INSTALLED, 0)
            return
        }

        val outcome = removeToolchainFiles(name, manifestObj, state, requireBaseLibListing = true)
        if (outcome == RemovalOutcome.UNCHANGED) {
            manager.failRemovalState(packName)
            return
        }

        val viaPlay = !manager.usesHttpFallback()
        if (outcome == RemovalOutcome.PARTIAL) {
            val pendingMarker = manager.installMarkerFile(packName)
            val priorMarkerExisted = pathExistsNoFollow(pendingMarker)
            if (priorMarkerExisted) {
                val pending = runCatching { JSONObject(pendingMarker.readText()) }.getOrNull()
                val priorSettled = pending != null && pending.optString("name") == name &&
                    (toolchainPathsAreOwned(pending, manifestObj) ||
                        reclaimPartialCopy(
                            name,
                            pending,
                            reclaimSameRootOrphans = true,
                            restoreSymlinks = false,
                        ))
                if (!priorSettled) {
                    Logger.w(
                        manager.tag,
                        "Keeping $packName record: prior pending marker could not be settled during partial removal",
                    )
                    manager.failState(packName, ToolchainFailure.REMOVAL)
                    return
                }
            }
            manager.installMarkerDir.mkdirs()
            if (!writeAtomically(pendingMarker) { it.write(manifestObj.toString().toByteArray()) }) {
                Logger.e(manager.tag, "Could not record cleanup marker for partially removed $packName; keeping state record")
                manager.failState(packName, ToolchainFailure.STORAGE)
                return
            }
            val releaseGen = if (viaPlay) markPackReleasePending(packName) else null
            state.remove(idx)
            if (!manager.writeStateFile(state)) {
                if (!priorMarkerExisted) pendingMarker.delete()
                if (viaPlay) clearPackReleaseMarker(packName, dropPendingRelease = true)
                manager.failState(packName, ToolchainFailure.STORAGE)
                return
            }
            manager.regenerateDerivedFilesInternal()
            if (priorMarkerExisted) {
                manager.cancel(packName)
            }
            if (viaPlay) manager.releasePackInternal(packName, expectedGeneration = releaseGen)
            manager.failState(packName, ToolchainFailure.REMOVAL)
            return
        }

        val releaseGen = if (viaPlay) markPackReleasePending(packName) else null

        state.remove(idx)
        if (!manager.writeStateFile(state)) {
            if (viaPlay) clearPackReleaseMarker(packName, dropPendingRelease = true)
            manager.failState(packName, ToolchainFailure.STORAGE)
            return
        }
        manager.regenerateDerivedFilesInternal()

        val pendingMarker = manager.installMarkerFile(packName)
        if (pendingMarker.exists() || Files.isSymbolicLink(pendingMarker.toPath())) {
            val pending = runCatching { JSONObject(pendingMarker.readText()) }.getOrNull()
            if (pending != null && pending.optString("name") == name && reclaimPartialCopy(name, pending)) {
                pendingMarker.delete()
            }
            manager.cancel(packName)
        }
        if (viaPlay) {
            manager.releasePackInternal(packName, expectedGeneration = releaseGen)
        }

        Logger.i(manager.tag, "Uninstalled toolchain: $name")
        manager.reportState(packName, AssetPackStatus.NOT_INSTALLED, 0)
    }

    /**
     * Deletes from disk what [manifest] says the toolchain [name] put there,
     * preserving libraries and links shared with the base APK or other installed toolchains.
     */
    fun removeToolchainFiles(
        name: String,
        manifest: JSONObject,
        state: JSONArray,
        requireBaseLibListing: Boolean = false,
    ): RemovalOutcome {
        val filesDir = manager.context.filesDir
        val paths = toolchainRemovalPaths(manifest).associateWith { confinedToolchainPath(filesDir, it) }
        if (paths.values.any { it == null }) {
            Logger.w(manager.tag, "Keeping $name: a managed path has a redirected ancestor")
            return RemovalOutcome.UNCHANGED
        }

        val otherLibs = otherToolchainLibs(state, name)
        val otherLibSymlinks = otherToolchainLibSymlinks(state, name)
        val otherSymlinks = otherToolchainSymlinks(state, name)
        val otherBinaries = otherToolchainBinaries(state, name)

        val libs = manifest.optJSONArray("libs")
        val libNames = if (libs != null) (0 until libs.length()).map { libs.getString(it) } else emptyList()
        val unsharedLibs = libNames.filter { it !in otherLibs && it !in otherLibSymlinks }
        val baseAssets: Set<String>? = if (unsharedLibs.isNotEmpty()) {
            try {
                manager.context.assets.list("usr/lib")?.toSet()
            } catch (e: Exception) {
                Logger.w(manager.tag, "Cannot list base usr/lib assets; keeping all libs: ${e.message}")
                null
            }
        } else {
            emptySet()
        }
        if (requireBaseLibListing && unsharedLibs.isNotEmpty() && baseAssets == null) {
            Logger.w(manager.tag, "Keeping $name: base usr/lib asset listing unavailable before removal")
            return RemovalOutcome.UNCHANGED
        }

        paths.values.mapNotNullTo(HashSet()) { it?.parentFile }.forEach(::sweepStrayTemps)
        val expectedGone = paths.keys.toMutableSet()
        var deletedAny = false

        val symlinks = manifest.optJSONObject("symlinks")
        if (symlinks != null) {
            for (key in symlinks.keys()) {
                val rel = "usr/bin/$key"
                if (key in otherSymlinks || rel in otherBinaries) {
                    expectedGone.remove(rel)
                    Logger.i(manager.tag, "Keeping symlink $key: another installed toolchain declares it")
                    continue
                }
                val link = paths.getValue(rel)!!
                val linkExists = try { Os.lstat(link.absolutePath); true } catch (e: Exception) { false }
                if (linkExists && link.delete()) {
                    deletedAny = true
                    Logger.d(manager.tag, "Removed symlink: $key")
                }
            }
        }

        val binaries = manifest.optJSONArray("binaries")
        if (binaries != null) {
            for (i in 0 until binaries.length()) {
                val binPath = binaries.getString(i)
                val sharedAsSymlink = binPath.startsWith("usr/bin/") &&
                    binPath.removePrefix("usr/bin/") in otherSymlinks
                if (binPath in otherBinaries || sharedAsSymlink) {
                    expectedGone.remove(binPath)
                    Logger.i(manager.tag, "Keeping binary $binPath: another installed toolchain declares it")
                    continue
                }
                val binFile = paths.getValue(binPath)!!
                if (pathExistsNoFollow(binFile) && binFile.delete()) {
                    deletedAny = true
                    Logger.d(manager.tag, "Removed binary: $binPath")
                }
            }
        }

        val installRoot = manifest.optString("installRoot", "")
        if (installRoot.isNotEmpty()) {
            val dir = paths.getValue(installRoot)!!
            if (pathExistsNoFollow(dir)) {
                val beforeCount = countEntriesNoFollow(dir)
                val freed = StorageManager.deleteRecursive(dir, filesDir.parentFile)
                if (freed > 0L || countEntriesNoFollow(dir) < beforeCount) {
                    deletedAny = true
                    Logger.d(manager.tag, "Deleted install root: $installRoot")
                }
            }
        }

        for (path in ownedPaths(filesDir, manifest, manager.tag)) {
            val rel = path.relativeTo(filesDir).path
            val target = paths.getValue(rel)!!
            if (pathExistsNoFollow(target)) {
                val beforeCount = countEntriesNoFollow(target)
                val freed = StorageManager.deleteRecursive(target, filesDir.parentFile)
                if (freed > 0L || countEntriesNoFollow(target) < beforeCount) {
                    deletedAny = true
                    Logger.d(manager.tag, "Removed owned path: $rel")
                }
            }
        }

        val libSymlinks = manifest.optJSONObject("libSymlinks")
        if (libSymlinks != null) {
            for (linkName in libSymlinks.keys()) {
                val rel = "usr/lib/$linkName"
                if (linkName in otherLibSymlinks || linkName in otherLibs) {
                    expectedGone.remove(rel)
                    Logger.i(manager.tag, "Keeping lib symlink $linkName: another installed toolchain declares it")
                    continue
                }
                val linkFile = paths.getValue(rel)!!
                val linkExists = try { Os.lstat(linkFile.absolutePath); true } catch (e: Exception) { false }
                if (linkExists && linkFile.delete()) {
                    deletedAny = true
                    Logger.d(manager.tag, "Removed lib symlink: $linkName")
                }
            }
        }

        if (libNames.isNotEmpty()) {
            val baseShipped = baseAssets?.plus(otherLibs)?.plus(otherLibSymlinks)
            val safeToRemove = toolchainLibsSafeToRemove(libNames, baseShipped)
            val keep = libNames.toSet() - safeToRemove.toSet()
            for (libName in keep) {
                if (baseShipped != null || libName in otherLibs || libName in otherLibSymlinks) {
                    expectedGone.remove("usr/lib/$libName")
                }
                Logger.i(manager.tag, "Keeping $libName: the base install or another toolchain ships it too")
            }
            for (libName in safeToRemove) {
                val lib = paths.getValue("usr/lib/$libName")!!
                if (pathExistsNoFollow(lib) && lib.delete()) {
                    deletedAny = true
                }
            }
        }
        val allGone = expectedGone.all { path -> !pathExistsNoFollow(paths.getValue(path)!!) }
        return when {
            allGone -> RemovalOutcome.COMPLETE
            deletedAny -> RemovalOutcome.PARTIAL
            else -> RemovalOutcome.UNCHANGED
        }
    }

    /** Paths a manifest can remove, before shared-library preservation is applied. */
    fun toolchainRemovalPaths(manifest: JSONObject): Set<String> = buildSet {
        val filesDir = manager.context.filesDir
        manifest.optString("installRoot", "").takeIf { it.isNotEmpty() }?.let { add(it) }
        ownedPaths(filesDir, manifest, manager.tag).forEach { add(it.relativeTo(filesDir).path) }
        manifest.optJSONArray("binaries")?.let { values ->
            for (i in 0 until values.length()) add(values.getString(i))
        }
        manifest.optJSONArray("libs")?.let { values ->
            for (i in 0 until values.length()) add("usr/lib/${values.getString(i)}")
        }
        manifest.optJSONObject("symlinks")?.keys()?.forEach { add("usr/bin/$it") }
        manifest.optJSONObject("libSymlinks")?.keys()?.forEach { add("usr/lib/$it") }
    }

    fun toolchainPathsAreOwned(pending: JSONObject, owner: JSONObject): Boolean {
        val filesDir = manager.context.filesDir
        val owned = toolchainRemovalPaths(owner)
        val trees = ownedPaths(filesDir, owner, manager.tag).map { it.relativeTo(filesDir).path } +
            listOfNotNull(owner.optString("installRoot", "").takeIf { it.isNotEmpty() })
        return toolchainRemovalPaths(pending).all { path ->
            path in owned || trees.any { path.startsWith(it + "/") }
        }
    }

    /**
     * Deletes the install root of the record a reinstall just replaced, when the new
     * manifest names a different one, and the `usr/bin` links that were left pointing into it.
     */
    fun removeSupersededTree(replaced: JSONObject?, manifest: JSONObject) {
        val filesDir = manager.context.filesDir
        val oldRoot = replaced?.optString("installRoot", "").orEmpty()
        if (oldRoot.isEmpty() || oldRoot == manifest.optString("installRoot", "")) return
        val base = runCatching { filesDir.canonicalFile }.getOrNull() ?: return
        val old = File(base, oldRoot).absoluteFile
        val parent = old.parentFile ?: return
        if (runCatching { parent.canonicalFile }.getOrNull() != parent.normalize()) return
        val resolvedOld = runCatching { old.canonicalFile }.getOrNull() ?: return
        val new = runCatching {
            File(filesDir, manifest.optString("installRoot", "")).canonicalFile
        }.getOrNull() ?: return
        if (!resolvedOld.path.startsWith(base.path + File.separator)) return
        if (new.path == resolvedOld.path || new.path.startsWith(resolvedOld.path + File.separator) ||
            resolvedOld.path.startsWith(new.path + File.separator)
        ) return
        StorageManager.deleteRecursive(old, filesDir.parentFile)
        Logger.i(manager.tag, "Deleted the superseded install root: $oldRoot")
        val kept = manifest.optJSONObject("symlinks")
        val links = replaced?.optJSONObject("symlinks") ?: return
        for (linkName in links.keys()) {
            if (kept?.has(linkName) == true) continue
            val link = confinedToolchainPath(filesDir, "usr/bin/$linkName") ?: continue
            val dangling = try { Os.lstat(link.absolutePath); !link.exists() } catch (e: Exception) { false }
            if (dangling) link.delete()
        }
    }

    /**
     * Computes the subset of [manifest] strictly owned by [manifest] that neither belongs to nor
     * contains any path owned by [installed], while recording whether [manifest] overlaps any
     * installed executable or unowned parent/child tree.
     */
    private fun analyzeOrphans(manifest: JSONObject, installed: JSONObject): OrphanAnalysis {
        val filesDir = manager.context.filesDir
        val installedPaths = toolchainRemovalPaths(installed)
        val installedRoot = installed.optString("installRoot", "")
        val installedTrees = buildSet {
            installedRoot.takeIf { it.isNotEmpty() }?.let(::add)
            ownedPaths(filesDir, installed, manager.tag).forEach { add(it.relativeTo(filesDir).path) }
        }
        fun ownedByInstalled(path: String): Boolean =
            path in installedPaths || installedTrees.any { path == it || path.startsWith("$it/") }
        fun containsInstalled(tree: String): Boolean =
            installedPaths.any { it == tree || it.startsWith("$tree/") } ||
                installedTrees.any { it == tree || it.startsWith("$tree/") }

        var hasUnreclaimableOverlap = false
        var overlapsInstalledExecutable = false

        val orphan = JSONObject().put("name", manifest.optString("name", ""))
        val root = manifest.optString("installRoot", "")
        if (root.isNotEmpty()) {
            if (!ownedByInstalled(root) && !containsInstalled(root)) {
                orphan.put("installRoot", root)
            } else {
                overlapsInstalledExecutable = true
                if (root != installedRoot) {
                    hasUnreclaimableOverlap = true
                }
            }
        }
        val orphanOwned = JSONArray()
        for (path in ownedPaths(filesDir, manifest, manager.tag)) {
            val rel = path.relativeTo(filesDir).path
            if (!ownedByInstalled(rel) && !containsInstalled(rel)) {
                orphanOwned.put(rel)
            } else {
                overlapsInstalledExecutable = true
                if (containsInstalled(rel) && rel !in installedTrees ||
                    ownedByInstalled(rel) && rel !in installedPaths
                ) {
                    hasUnreclaimableOverlap = true
                }
            }
        }
        if (orphanOwned.length() > 0) orphan.put("ownedPaths", orphanOwned)

        manifest.optJSONArray("binaries")?.let { binaries ->
            val orphanBins = JSONArray()
            for (i in 0 until binaries.length()) {
                val bin = binaries.getString(i)
                if (!ownedByInstalled(bin) && !containsInstalled(bin)) {
                    orphanBins.put(bin)
                } else {
                    overlapsInstalledExecutable = true
                }
            }
            if (orphanBins.length() > 0) orphan.put("binaries", orphanBins)
        }
        manifest.optJSONObject("symlinks")?.let { symlinks ->
            val orphanLinks = JSONObject()
            for (key in symlinks.keys()) {
                if (!ownedByInstalled("usr/bin/$key")) orphanLinks.put(key, symlinks.getString(key))
            }
            if (orphanLinks.length() > 0) orphan.put("symlinks", orphanLinks)
        }
        manifest.optJSONObject("libSymlinks")?.let { libSymlinks ->
            val orphanLibLinks = JSONObject()
            for (key in libSymlinks.keys()) {
                if (!ownedByInstalled("usr/lib/$key")) orphanLibLinks.put(key, libSymlinks.getString(key))
            }
            if (orphanLibLinks.length() > 0) orphan.put("libSymlinks", orphanLibLinks)
        }
        manifest.optJSONArray("libs")?.let { libs ->
            val orphanLibs = JSONArray()
            for (i in 0 until libs.length()) {
                val lib = libs.getString(i)
                if (!ownedByInstalled("usr/lib/$lib")) orphanLibs.put(lib)
            }
            if (orphanLibs.length() > 0) orphan.put("libs", orphanLibs)
        }
        return OrphanAnalysis(orphan, hasUnreclaimableOverlap, overlapsInstalledExecutable)
    }

    /**
     * Restores shared `usr/bin` and `usr/lib` symlinks declared by both [installed] and [aborted]
     * when the aborted upgrade removed them or left them dangling at a reclaimed tree.
     */
    private fun restoreSharedSymlinks(installed: JSONObject, aborted: JSONObject) {
        val filesDir = manager.context.filesDir
        val installedLinks = installed.optJSONObject("symlinks")
        val abortedLinks = aborted.optJSONObject("symlinks")
        if (installedLinks != null && abortedLinks != null) {
            for (key in installedLinks.keys()) {
                if (!abortedLinks.has(key) || !isSinglePathSegment(key)) continue
                val targetRel = installedLinks.optString(key, "")
                val linkFile = confinedUsrPath(filesDir, "usr/bin/$key") ?: continue
                if (!isConfinedUsrTarget(filesDir, targetRel)) continue
                val targetFile = confinedUsrPath(filesDir, targetRel) ?: continue
                if (!targetFile.exists()) continue
                val expected = "${manager.filesDir}/$targetRel"
                val abortedTargetRel = abortedLinks.optString(key, "")
                val abortedExpected = if (abortedTargetRel.isNotEmpty()) "${manager.filesDir}/$abortedTargetRel" else null
                val missing = !pathExistsNoFollow(linkFile)
                val current = if (missing) null else runCatching { Os.readlink(linkFile.absolutePath) }.getOrNull()
                val stale = current != null && current != expected &&
                    (current == abortedExpected || !File(current).exists())
                if (missing || stale) {
                    runCatching {
                        if (!missing) linkFile.delete()
                        File(filesDir, "usr/bin").mkdirs()
                        Os.symlink(expected, File(filesDir, "usr/bin/$key").absolutePath)
                    }
                }
            }
        }

        val installedLibLinks = installed.optJSONObject("libSymlinks")
        val abortedLibLinks = aborted.optJSONObject("libSymlinks")
        if (installedLibLinks != null && abortedLibLinks != null) {
            for (key in installedLibLinks.keys()) {
                if (!abortedLibLinks.has(key) || !isSinglePathSegment(key)) continue
                val targetName = installedLibLinks.optString(key, "")
                val targetRel = "usr/lib/$targetName"
                val linkFile = confinedUsrPath(filesDir, "usr/lib/$key") ?: continue
                if (!isConfinedUsrTarget(filesDir, targetRel)) continue
                val targetFile = confinedUsrPath(filesDir, targetRel) ?: continue
                if (!targetFile.exists()) continue
                val expected = File(filesDir, targetRel).absolutePath
                val abortedTargetName = abortedLibLinks.optString(key, "")
                val abortedExpected = if (abortedTargetName.isNotEmpty()) {
                    File(filesDir, "usr/lib/$abortedTargetName").absolutePath
                } else {
                    null
                }
                val missing = !pathExistsNoFollow(linkFile)
                val current = if (missing) null else runCatching { Os.readlink(linkFile.absolutePath) }.getOrNull()
                val stale = current != null && current != expected &&
                    (current == abortedExpected || !File(current).exists())
                if (missing || stale) {
                    runCatching {
                        if (!missing) linkFile.delete()
                        File(filesDir, "usr/lib").mkdirs()
                        Os.symlink(expected, File(filesDir, "usr/lib/$key").absolutePath)
                    }
                }
            }
        }
    }

    /**
     * Deletes the files an install that did not finish had already written, when
     * nothing else lays claim to them.
     */
    fun reclaimPartialCopy(
        name: String,
        manifest: JSONObject,
        reclaimSameRootOrphans: Boolean = false,
        restoreSymlinks: Boolean = true,
    ): Boolean = synchronized(ToolchainManager.stateLock) {
        val state = manager.readableStateFile() ?: return@synchronized false
        val installed = (0 until state.length()).mapNotNull { state.optJSONObject(it) }
            .firstOrNull { it.optString("name") == name }
        if (installed != null) {
            Logger.w(manager.tag, "Keeping the files of $name: the record still names it")
            val installId = manifest.optString(ToolchainManager.KEY_INSTALL_ID)
            val installedId = installed.optString(ToolchainManager.KEY_INSTALL_ID)
            if (installId.isNotEmpty() && installedId == installId &&
                toolchainPathsAreOwned(manifest, installed)
            ) return@synchronized true

            val manifestRoot = manifest.optString("installRoot", "")
            val installedRoot = installed.optString("installRoot", "")
            val crossRoot = manifestRoot.isNotEmpty() && installedRoot.isNotEmpty() && manifestRoot != installedRoot
            val analysis = analyzeOrphans(manifest, installed)
            val hasOrphans = toolchainRemovalPaths(analysis.orphan).isNotEmpty()
            val canReclaimOrphans = hasOrphans && (
                crossRoot ||
                    (reclaimSameRootOrphans && (installId.isEmpty() || installId != installedId))
                )
            var reclaimedAllOrphans = false
            if (canReclaimOrphans) {
                val outcome = removeToolchainFiles(name, analysis.orphan, state)
                if (restoreSymlinks && outcome != RemovalOutcome.UNCHANGED) {
                    restoreSharedSymlinks(installed, manifest)
                }
                reclaimedAllOrphans = outcome == RemovalOutcome.COMPLETE
                if (reclaimedAllOrphans) {
                    Logger.i(manager.tag, "Reclaimed orphaned partial upgrade files for $name")
                } else {
                    Logger.w(manager.tag, "Could not fully reclaim orphaned partial upgrade files for $name")
                }
            } else if (restoreSymlinks && crossRoot) {
                restoreSharedSymlinks(installed, manifest)
            }

            if (analysis.overlapsInstalledExecutable || !canReclaimOrphans) {
                val asked = installed.has(ToolchainManager.KEY_EXEC_RESET_FOR) &&
                    installed.optString(ToolchainManager.KEY_EXEC_RESET_FOR) == installId
                if (!asked) {
                    installed.put(ToolchainManager.KEY_EXEC_REPAIRED, false)
                    installed.put(ToolchainManager.KEY_EXEC_RESET_FOR, installId)
                    if (!manager.writeStateFile(state)) return@synchronized false
                }
            }
            if (canReclaimOrphans && reclaimedAllOrphans && !analysis.hasUnreclaimableOverlap) {
                return@synchronized true
            }
            return@synchronized false
        }
        val settled = removeToolchainFiles(name, manifest, state) == RemovalOutcome.COMPLETE
        if (settled) {
            Logger.i(manager.tag, "Reclaimed the partial $name files")
        } else {
            Logger.w(manager.tag, "Could not fully reclaim the partial $name files")
        }
        settled
    }

    /**
     * Gives back the tree of an install the system stopped part-way through.
     */
    fun reclaimInterruptedInstallsSync() {
        val markers = manager.installMarkerDir.listFiles() ?: return
        for (marker in markers) {
            if (!marker.name.endsWith(".json")) continue
            val packName = marker.name.removeSuffix(".json")
            val claimed = synchronized(ToolchainManager.packOperationLock) {
                !ToolchainManager.httpDownloads.containsKey(packName) &&
                    manager.claimPackInternal(packName, readsDeliveredPack = false)
            }
            if (!claimed) continue
            try {
                val manifest = runCatching { JSONObject(marker.readText()) }.getOrNull()
                val name = manifest?.optString("name", "").orEmpty()
                if (manifest != null && name.isNotEmpty()) {
                    Logger.i(manager.tag, "Reclaiming an install of $name that did not finish")
                    if (!reclaimPartialCopy(name, manifest)) continue
                }
                marker.delete()
            } finally {
                manager.releasePackClaimInternal(packName)
            }
        }
    }

    /**
     * Removes a toolchain the app no longer offers.
     */
    fun removeRetiredToolchainsSync() = synchronized(ToolchainManager.stateLock) {
        val installed = manager.readStateFile()
        val present = mutableListOf<String>()
        for (i in 0 until installed.length()) {
            val name = installed.optJSONObject(i)?.optString("name").orEmpty()
            if (name.isNotEmpty() && toolchainShortName(name) in RETIRED_TOOLCHAINS) {
                present.add(name)
            }
        }
        for (name in present) {
            Logger.i(manager.tag, "Removing $name: this build no longer offers it")
            uninstallLocked(name)
        }
    }
}
