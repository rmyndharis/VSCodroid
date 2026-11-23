package com.vscodroid.setup

import com.vscodroid.R
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Locale
import com.vscodroid.setup.FirstRunSetup.Companion.EXTENSIONS_PROGRESS_START
import com.vscodroid.setup.FirstRunSetup.Companion.EXTENSIONS_PROGRESS_END

// Bundled extension extraction, profile repointing, and extensions.json manifest management for [FirstRunSetup].

internal fun FirstRunSetup.bundledExtensionsToExtract(extensionsDir: File, bundled: List<String>): List<String> {
    // Which of these need unpacking is [bundledDirsToExtract]'s decision and
    // its doc carries the reasoning. The short version: asking `exists()`
    // alone asked whether a directory carrying this version string had ever
    // been unpacked, never whether it holds the bytes this APK carries, so
    // an extension edited without a version bump reached clean installs and
    // nobody else -- and nothing noticed, because the extension tests in the
    // pull-request and release workflows read the asset rather than the
    // device.
    //
    // Read before extraction on purpose: the listing taken after it is the
    // one the superseded sweep below needs, and it must see what extraction
    // just created.
    // Directories only, and the filter is the point. `list()` returns files
    // and directories alike, so a plain file sitting at an extension's path
    // would answer "already installed" for a fetched one and drop it from
    // the list -- never unpacked, never retried, and nothing said. The
    // sweeps below take the unfiltered listing on purpose: for them a stray
    // file is something to remove, not something to mistake for an install.
    val installed = extensionsDir.listFiles()
        ?.filter { it.isDirectory }
        ?.map { it.name }
        ?: emptyList()
    // And which of those are not installs at all, but what a killed unpack
    // left behind. Only names this build bundles are asked the question:
    // the same listing feeds the newer-version decision inside
    // [bundledDirsToExtract], and a directory belonging to a user's own
    // gallery install is not ours to call unfinished.
    val abandoned = installed
        .filter { it in bundled && unpackWasAbandoned(File(extensionsDir, it)) }
        .toSet()
    // And which fetched ids the user has removed, so they are not unpacked
    // into a directory nothing would list. The manifest reconcile below
    // already declines to relist such an id, on the same two facts: it was
    // bundled before, and no entry names it now. Unpacking it anyway wrote
    // 29 MiB for the Python extension alone, unlisted, that only a version
    // bump ever swept, and the bump then wrote it once more.
    val manifestFile = File(extensionsDir, "extensions.json")
    val uninstalled = listedExtensionIds(manifestFile)
        ?.let { listed -> previouslyBundledIds() - listed }
        ?: emptySet()
    // And which directories the default profile lists, the only copies it can run.
    // Profiles share this directory, so what else is on disk is not necessarily
    // theirs; see [bundledDirsToExtract]. Unknown when an entry names no
    // directory, as [otherProfilesExtensionDirs] reads one.
    val listedByDefault = manifestEntryDirs(manifestFile)
        ?.takeIf { entries -> entries.none { it.second.isEmpty() } }
        ?.mapTo(HashSet()) { it.second }
    return bundledDirsToExtract(installed, bundled, abandoned, uninstalled, listedByDefault)
}


internal fun FirstRunSetup.extractBundledExtensionsImpl() {
    val extensionsDir = File(context.filesDir, "home/.vscodroid/extensions")
    extensionsDir.mkdirs()

    val bundled = try {
        context.assets.list("extensions") ?: emptyArray()
    } catch (e: IOException) {
        Logger.d(tag, "No bundled extensions in assets")
        emptyArray()
    }

    if (bundled.isEmpty()) {
        Logger.d(tag, "No bundled extensions found")
        return
    }

    val manifestFile = File(extensionsDir, "extensions.json")
    val toExtract = bundledExtensionsToExtract(extensionsDir, bundled.toList())
    // One counter across the whole loop, so the bar reflects the step rather
    // than restarting per extension. Its total is what the APK bundles, so a
    // run re-unpacking only some of the directories stops the bar short of
    // the step's end instead of over-running it, which is the safe direction.
    val progress = byteProgress(
        context.getString(R.string.setup_step_extensions),
        EXTENSIONS_PROGRESS_START,
        EXTENSIONS_PROGRESS_END,
        bundledExtensionBytes,
    )
    for (name in toExtract) {
        // Merges rather than emptying the directory first. That leaves a
        // file this build no longer ships behind, which is inert because
        // `package.json` does not name it, and it is the better of the two
        // failures: clearing first would leave no extension at all if the
        // copy were interrupted.
        //
        // A failed unpack has to do two things, and doing either alone
        // leaves the defect standing.
        //
        // THROW, because extractAssetFile logs a failed copy and carries
        // on. That is right for the server tree, where one missing file is
        // not worth abandoning a 390 MB unpack, and wrong here: a copy that
        // silently does not happen leaves the previous release's code in
        // place, which is what this loop exists to replace. Throwing puts
        // it in front of runSetupLocked's catch, upstream of
        // markSetupComplete().
        //
        // REMOVE WHAT THIS ATTEMPT CREATED, because throwing alone does not
        // make the retry retry. extractAssetDir creates the destination
        // before copying into it, so a partial copy leaves a directory --
        // and [bundledDirsToExtract] keeps a fetched extension only while
        // its directory is ABSENT. The next attempt would drop it from the
        // list, run an empty loop, throw nothing, and let setup certify a
        // half-unpacked extension that the manifest then lists from the
        // package.json that did land: installed to look at, dead on every
        // activation, permanently.
        //
        // Only what nothing else would name again, which is not the same
        // question as "what this attempt created". A directory that was
        // already there belongs to the previous release and its files were
        // each replaced atomically, so what survives is whole even if mixed,
        // and ours are re-unpacked unconditionally so a mixed one heals next
        // run. A fetched one used to be in this list only because it was
        // absent, which is what made "did this attempt create it" the whole
        // test -- and [abandoned] above is exactly what stopped that being
        // true: a present directory a kill left half full is now selected
        // too, and for that one the delete is suppressed while the mark that
        // replaces it is written best-effort a few lines below. So the test
        // is [failedUnpackMustBeRemoved], which asks whether the next run
        // would select this name again and leaves the delete as the fallback
        // for when nothing would.
        //
        // That makes the retry rest on the delete succeeding, in the
        // condition that caused the failure. Two things could defeat it:
        //
        //   a full disk does not. Measured on ext4 -- partition filled to
        //   zero, a 64 KB control write refused with ENOSPC to prove it,
        //   and the recursive removal then succeeding. Unlinking releases
        //   space rather than needing it. Limits: ext4 on an emulator,
        //   f2fs untested, and it measured the removal rather than this
        //   code.
        //
        //   `listFiles()` answering null would. [removeExtensionDir] walks
        //   with it and removes nothing, so the path is still there and
        //   the line below fires. Reachable here only through hardware
        //   failure: the tree is created by mkdirs moments earlier in this
        //   same run, by this process, under filesDir, with nothing
        //   changing its mode in between. A plain file at the path is not
        //   this case; the walk yields it and removes it.
        //
        // So the error line below reports a state nobody expects rather
        // than guarding one we do. Usually the extension is still retried
        // even then: the directory it could not remove carries the in-flight
        // mark written below, which puts the name back on this list, and
        // before that mark existed the next attempt skipped it for good. The
        // exception is the one case where the mark could not be written
        // either, which is the only reason the delete was attempted for a
        // directory that was already there. Two failures at once leave
        // nothing to retry from, so the line says which of the two happened
        // rather than promising a retry it cannot know about.
        val dest = File(extensionsDir, name)
        // isDirectory, not exists, and for the same reason createDirectories
        // asks it: the question is whether a previous release left something
        // worth keeping, and a plain file at this path is not that. It would
        // answer exists() with yes, be preserved rather than cleared, and
        // then defeat mkdirs on every future attempt -- unrecoverable for a
        // fetched extension, since the retry drops it from the list. Reading
        // it as "nothing usable here" clears it and lets the retry work.
        val existedBefore = dest.isDirectory
        // The other half of the retry, and the half that survives a kill.
        // Everything above answers an unpack that FAILED; nothing answers
        // one that was never allowed to finish. Setup runs in
        // SplashActivity's scope, and the foreground hold it takes makes a
        // kill during this copy rare rather than impossible: a low-memory
        // kill still ends it outright, and the directory extractAssetDir created is
        // then the whole of what a later run has to go on: presence, which
        // is the only staleness test a fetched extension gets, reads a few
        // hundred of 3787 files as installed and never touches it again.
        //
        // A file this app writes inside the directory before the copy and
        // removes after it is what tells the two apart. It costs one create
        // and one unlink per extension that actually needs unpacking, it is
        // read by [unpackWasAbandoned] and by nothing else, and the answer
        // it gives on a device that predates it is "finished", which is the
        // reading every install already had.
        //
        // Resuming over the partial tree rather than clearing it first is
        // deliberate, and it is the same choice the top of this loop makes:
        // extractAssetFile writes every file through a rename, so no file
        // on disk is half of anything, and merging the missing ones in
        // yields a whole tree. Clearing would spend the copy again and open
        // a window where the extension is not there at all.
        dest.mkdirs()
        val marker = File(dest, UNPACK_MARKER_NAME)
        if (!marker.isFile && !runCatching { marker.createNewFile() }.getOrDefault(false)) {
            // Not fatal: the copy below is worth attempting either way, and
            // the failure path below compensates by deleting a directory it
            // could not mark. Nothing compensates for a process kill, which
            // is what the mark is for, so this is said out loud: an unpack
            // nobody marked is exactly as recoverable as it was before any
            // of this existed.
            Logger.w(
                tag,
                "Could not mark $name as being unpacked; a process kill during its copy " +
                    "would leave it looking installed",
            )
        }
        if (!extractAssetDirInternal("extensions/$name", "home/.vscodroid/extensions/$name", progress, required = true)) {
            if (failedUnpackMustBeRemoved(name, existedBefore, marker.isFile) &&
                !removeExtensionDir(dest, context.filesDir.parentFile)
            ) {
                Logger.e(
                    tag,
                    "Could not remove the partially unpacked $name. The next attempt " +
                        "unpacks it again only while the unpacking mark is on it, and " +
                        "that mark is ${if (marker.isFile) "there" else "not"}.",
                )
            }
            val cause = firstWriteFailure?.let { ": $it" }.orEmpty()
            throw IOException("could not unpack bundled extension $name$cause")
        }
        // Only now is the directory an install. A clear that fails costs
        // one wasted re-copy on the next run, never a broken extension,
        // which is why it is a warning and not a throw.
        if (marker.exists() && !marker.delete()) {
            Logger.w(tag, "Could not clear the unpacking mark on $name; it will be unpacked again")
        }
    }

    val present = extensionsDir.list()?.toList() ?: emptyList()
    val superseded = supersededExtensionDirs(present, bundled.toList())
    // Before the profiles are read for the sweep below: it is what lets the old copies
    // of OUR extensions go, by taking them off the lists that were keeping them.
    repointOwnExtensionsInProfiles(extensionsDir, bundled.toList(), superseded, present)
    val elsewhere = otherProfilesExtensionDirs()
    var removedSuperseded = 0
    for (name in superseded) {
        if (elsewhere == null || name in elsewhere) continue
        if (removeExtensionDir(File(extensionsDir, name), context.filesDir.parentFile)) {
            removedSuperseded++
            Logger.i(tag, "Removed superseded bundled extension: $name")
        }
    }

    // Disjoint from superseded by construction: that set is versions of ids
    // still bundled, this one is our ids that stopped being bundled at all.
    for (name in retiredOwnExtensionDirs(present, bundled.toList())) {
        if (removeExtensionDir(File(extensionsDir, name), context.filesDir.parentFile)) {
            Logger.i(tag, "Removed retired bundled extension: $name")
        }
    }

    // And the case neither of those reaches: an extension this project
    // FETCHED and then stopped shipping. Its publisher is not ours, so
    // retirement cannot see it, and no version of its id is bundled any
    // more, so supersession cannot either. Nothing removed it and nothing
    // ever would. Ordered before the manifest work below on purpose --
    // reconcileExtensionsManifest drops any entry whose directory is gone,
    // so removing the directory here is also what unlists it.
    // Once per id, not once per update. See [retiredIdsToSweep]: this
    // cannot distinguish bare legacy names from a deliberate reinstall.
    // Gallery names with a target-platform suffix are always preserved.
    val sweptAlready = retiredExtensionsSwept()
    val owed = retiredIdsToSweep(sweptAlready)
    if (owed.isNotEmpty()) {
        for (name in retiredFetchedExtensionDirs(present, owed)) {
            if (removeExtensionDir(File(extensionsDir, name), context.filesDir.parentFile)) {
                Logger.i(tag, "Removed a bundled extension this build no longer ships: $name")
            }
        }
        // Recorded whether or not anything was found: the debt is discharged
        // by looking, and a device that never had the directory must not go
        // on looking for it at every update either.
        //
        // Committed, as the class header requires of every write here. The
        // interval between the delete above and this record is exactly the
        // one a kill lands in, and apply() would have left it in a flush
        // window the process may not outlive.
        markRetiredExtensionsSwept(HashSet(sweptAlready + owed))
    }

    // Bundled copies an earlier update unpacked beside the user's own gallery
    // install, which nothing lists. [orphanedBundledCopies] carries the rules.
    // The disk is read again here, after every sweep above, because the rule
    // asks whether the user's copy is on disk NOW; and nothing is removed
    // unless every profile's manifest could be read.
    // Superseded default entries will be replaced even when another profile
    // needs the old directory, so they cannot make the new copy an orphan.
    val listed = manifestEntryDirs(manifestFile)?.filterNot { it.second in superseded }
    if (listed != null && elsewhere != null) {
        val onDisk = extensionsDir.list()?.toList() ?: emptyList()
        for (name in orphanedBundledCopies(onDisk, bundled.toList(), listed, elsewhere)) {
            if (removeExtensionDir(File(extensionsDir, name), context.filesDir.parentFile)) {
                Logger.i(tag, "Removed $name: an earlier update unpacked it beside the user's own copy, and nothing lists it")
            }
        }
    }

    // The server manages this file for marketplace installs, so it is never
    // regenerated wholesale. But it is the default profile's manifest (the
    // scanner shows only what is listed in it), and bundled extensions
    // change with app upgrades while the file survives them. Reconcile:
    // entries whose directory is gone are unloadable and dropped, freshly
    // extracted bundled versions gain an entry, everything else stays
    // exactly as the server wrote it.
    //
    // What this build bundles, and deliberately not what reached the disk;
    // [bundledExtensionIds] carries the reasoning. Computed once so both
    // writers of the record agree: an id in one and missing from the other
    // is read later as an extension the user removed.
    val bundledIds = bundledExtensionIds(bundled.toList())

    if (!manifestFile.exists()) {
        generateExtensionsManifestImpl(extensionsDir, bundled)
        // Recorded on this path too. A fresh install lists everything, so
        // nothing here needs the history, but the *next* upgrade does, and
        // an install that never wrote it would read an empty set and treat
        // every bundled extension as new.
        rememberBundledIds(bundledIds)
    } else {
        reconcileExtensionsManifestImpl(manifestFile, extensionsDir, bundled, bundledIds, superseded)
    }

    // Reports the decision. Reaching this line now also means every one of
    // those unpacks succeeded, since the loop above throws otherwise.
    Logger.i(tag, "Bundled extensions: ${toExtract.size} of ${bundled.size} needed unpacking, " +
        "$removedSuperseded superseded removed")
}


/**
 * Each entry of an `extensions.json` as its identifier, lowercased, and the
 * directory it names, or null when the file cannot say.
 *
 * The directory is read the way [reconcileExtensionsManifest] reads it; an
 * entry that names none carries "". Null for a missing or unparseable file,
 * because callers delete on the strength of what this lists.
 */
internal fun FirstRunSetup.manifestEntryDirs(manifestFile: File): List<Pair<String, String>>? {
    if (!manifestFile.isFile) return null
    return try {
        val entries = JSONArray(manifestFile.readText())
        (0 until entries.length()).map { i ->
            val entry = entries.getJSONObject(i)
            val id = entry.optJSONObject("identifier")?.optString("id").orEmpty().lowercase(Locale.ROOT)
            val path = entry.optJSONObject("location")?.optString("path").orEmpty()
            val dir = entry.optString("relativeLocation")
                .ifEmpty { if (path.isEmpty()) "" else File(path).name }
            id to dir
        }
    } catch (e: Exception) {
        Logger.d(tag, "Could not read ${manifestFile.path}: ${e.message}")
        null
    }
}


/**
 * The directories every profile other than the default one lists, or null
 * when one of their manifests cannot be read or lists an entry that names no
 * directory.
 *
 * Profiles share the extensions directory and keep a manifest each under
 * `data/User/profiles/<id>/`, and a profile created while a bundled copy was
 * still listed goes on naming it after the default profile moved on. A
 * profile whose manifest this cannot read, or one with an entry it cannot place,
 * may name anything, so the answer is then "unknown" rather than "nothing".
 */
internal fun FirstRunSetup.otherProfilesExtensionDirs(): Set<String>? {
    val profilesDir = File(Environment.getUserDataDir(context), "data/User/profiles")
    if (Files.notExists(profilesDir.toPath())) return emptySet()
    val profiles = profilesDir.listFiles() ?: return null
    val dirs = mutableSetOf<String>()
    for (profile in profiles) {
        val manifest = File(profile, "extensions.json")
        if (Files.notExists(manifest.toPath())) continue
        val entries = manifestEntryDirs(manifest) ?: return null
        if (entries.any { it.second.isEmpty() }) return null
        dirs += entries.map { it.second }
    }
    return dirs
}


/**
 * Points every named profile's entries for superseded copies of OUR extensions at the
 * copies this build bundles; [repointOwnExtensionEntries] has the rules.
 *
 * A profile whose manifest cannot be read, parsed or written is left exactly as it
 * was, and the sweep that follows then keeps whatever it lists: [otherProfilesExtensionDirs]
 * answers unknown for a manifest it cannot read, and a manifest this could not rewrite
 * still names the old directory. Failing to update a profile therefore costs the old
 * extension staying, which is what happened before, and never an extension removed
 * from under a profile. The default profile is not touched here: its own reconcile
 * does this for it.
 */
internal fun FirstRunSetup.repointOwnExtensionsInProfiles(
    extensionsDir: File,
    bundled: List<String>,
    superseded: List<String>,
    present: List<String>,
) {
    val profilesDir = File(Environment.getUserDataDir(context), "data/User/profiles")
    val profiles = profilesDir.listFiles() ?: return
    val onDisk = bundled.filter { it in present }
    for (profile in profiles) {
        val manifest = File(profile, "extensions.json")
        if (!manifest.isFile) continue
        try {
            val rewritten = repointOwnExtensionEntries(
                manifest.readText(), superseded, onDisk, extensionsDir.absolutePath,
            ) ?: continue
            if (writeAtomically(manifest) { it.write(rewritten.toByteArray()) }) {
                Logger.i(tag, "Pointed a profile's entries for our own extensions at the current copies")
            } else {
                Logger.w(tag, "Could not rewrite a profile's extension list; its old copies stay")
            }
        } catch (e: Exception) {
            Logger.w(tag, "Could not update a profile's extension list: ${e.javaClass.simpleName}")
        }
    }
}


/**
 * The identifiers `extensions.json` lists, or null when it cannot say.
 *
 * Null rather than empty for a manifest that is missing or unparseable, and
 * the direction matters: the one reader subtracts this from the record to
 * find what the user removed, and an empty answer would make every fetched
 * extension ever bundled look removed. A manifest the server has not
 * written yet, or a truncated one [reconcileExtensionsManifest] also gives
 * up on, says nothing about what the user chose.
 */
internal fun FirstRunSetup.listedExtensionIds(manifestFile: File): Set<String>? {
    if (!manifestFile.isFile) return null
    return try {
        val entries = JSONArray(manifestFile.readText())
        // Lowercased to meet the record: manifestEntryFor writes ids from the
        // lowercased package.json halves, and VS Code's own gallery ids are
        // lowercase, but nothing here should depend on the second.
        (0 until entries.length()).mapNotNullTo(mutableSetOf()) { i ->
            entries.getJSONObject(i).optJSONObject("identifier")?.optString("id")
                ?.takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)
        }
    } catch (e: Exception) {
        Logger.d(tag, "Could not read extensions.json, so no bundled extension reads as removed: ${e.message}")
        null
    }
}


internal fun FirstRunSetup.generateExtensionsManifestImpl(extensionsDir: File, bundledDirs: Array<String>) {
    val entries = JSONArray()
    for (dirName in bundledDirs) {
        manifestEntryFor(extensionsDir, dirName)?.let { entries.put(it) }
    }

    // Atomic, on the same grounds reconcileExtensionsManifest states for the
    // other write of this file: a truncated manifest is read as an empty
    // extension list, so the extensions vanish rather than the write
    // visibly failing. Here it is also unrecoverable. Anything at this path
    // sends the next launch down the reconcile branch instead of this one,
    // and reconciliation cannot parse a half-written document -- it catches
    // and returns -- so the manifest is never regenerated and the list stays
    // empty for the life of the install.
    // Thrown rather than returned, and the caller is the reason. Returning
    // only ends this function: extractBundledExtensions would go on to
    // rememberBundledIds(), persisting the bundled identifier set for an
    // install that has no manifest. That record outlives the process and
    // the upgrade, and it is what a later reconcile uses to tell an
    // extension the user uninstalled from one this app has never shipped --
    // so an identifier recorded with no entry beside it reads as
    // deliberately removed and is never listed again.
    //
    // "Nothing else creates extensions.json, so the reconcile branch is
    // unreachable" was the reasoning that made that look harmless, and the
    // comment fourteen lines above the branch refutes it in this file's own
    // words: the server manages this file for marketplace installs. One
    // install from the gallery creates it, and the next upgrade then reads
    // every bundled extension as removed. Throwing also skips
    // markSetupComplete(), so the whole setup is retried instead.
    val manifestFile = File(extensionsDir, "extensions.json")
    if (!writeAtomically(manifestFile) { it.write(entries.toString(2).toByteArray()) }) {
        // The same type its twin throws, though nothing here needs a type to
        // escape a catch: this function sits outside any. Uniform because
        // the two are one decision written twice, and a reader who wrapped
        // this branch to match the reconcile branch beside it would
        // otherwise turn a plain IOException back into a swallowed one.
        throw ManifestWriteFailed("could not write $manifestFile")
    }
    Logger.i(tag, "Generated extensions.json with ${entries.length()} entries")
}


internal fun FirstRunSetup.manifestEntryFor(extensionsDir: File, dirName: String): JSONObject? {
    val extDir = File(extensionsDir, dirName)
    val pkgFile = File(extDir, "package.json")
    if (!pkgFile.exists()) {
        Logger.d(tag, "No package.json in $dirName, skipping manifest entry")
        return null
    }

    return try {
        val pkg = JSONObject(pkgFile.readText())
        val publisher = pkg.optString("publisher", "")
        val name = pkg.optString("name", "")
        val version = pkg.optString("version", "")

        if (publisher.isEmpty() || name.isEmpty()) return null

        JSONObject().apply {
            put(
                "identifier",
                JSONObject().put("id", "${publisher.lowercase(Locale.ROOT)}.${name.lowercase(Locale.ROOT)}"),
            )
            put("version", version)
            put("location", JSONObject().apply {
                put("\$mid", 1)
                put("path", extDir.absolutePath)
                put("scheme", "file")
            })
            put("relativeLocation", dirName)
            put("metadata", JSONObject().apply {
                put("installedTimestamp", System.currentTimeMillis())
                put("source", "bundled")
                // Our own extensions are the app, not ones a user chose, and
                // without this the workbench offers to uninstall them: its
                // Uninstall action disables itself only for a builtin
                // (`if(this.extension.isBuiltin){this.enabled=!1;return}` in
                // workbench.js), and the server derives that from what is
                // written here (`isBuiltin:e.isBuiltin||!!e.metadata?.isBuiltin`
                // in server-main.js).
                //
                // Removing one is not recoverable from inside the editor.
                // Uninstalling the bridge takes with it the only route to the
                // device folder picker, the toolchain screen and the storage
                // tools; no gallery publishes `vscodroid.*`, so the Extensions
                // view cannot offer it back. The directory returns on the next
                // app update, because [bundledDirsToExtract] exempts this prefix
                // from the "the user removed it, leave it removed" rule, but
                // [bundledIdsToRelist] declines an id [rememberBundledIds] has
                // already recorded, so it sits there unlisted and unloaded.
                //
                // Relisting instead would be the wrong half to fix: that rule
                // exists so a downloaded extension a user deliberately removed
                // does not come back on every update. This keeps that promise
                // and takes ours out of the argument entirely.
                if (dirName.startsWith(OWN_EXTENSION_PREFIX)) put("isBuiltin", true)
            })
        }
    } catch (e: Exception) {
        Logger.d(tag, "Failed to parse $dirName/package.json: ${e.message}")
        null
    }
}


/**
 * Brings the manifest back in line with the directories after an upgrade
 * swapped bundled extension versions underneath it. Conservative on
 * purpose: an entry survives unless its directory is verifiably gone or a
 * bundled upgrade supersedes it. An older directory may still be needed by
 * another profile. A bundled directory is only added to replace an entry just
 * dropped, never for an identifier with no entry at all, which is an
 * extension the user uninstalled, nor over a surviving entry, so a user's
 * own newer install keeps winning over the bundled copy.
 */
internal fun FirstRunSetup.reconcileExtensionsManifestImpl(
    manifestFile: File,
    extensionsDir: File,
    bundledDirs: Array<String>,
    bundledIds: List<String>,
    supersededDirs: List<String>,
) {
    try {
        val entries = JSONArray(manifestFile.readText())
        val kept = JSONArray()
        val keptIds = mutableSetOf<String>()
        val droppedIds = mutableSetOf<String>()
        var dropped = 0
        var metadataUpdated = false

        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val path = entry.optJSONObject("location")?.optString("path").orEmpty()
            val dirName = entry.optString("relativeLocation")
                .ifEmpty { if (path.isEmpty()) "" else File(path).name }
            if (dirName in supersededDirs ||
                (dirName.isNotEmpty() && !File(extensionsDir, dirName).exists())
            ) {
                dropped++
                entry.optJSONObject("identifier")?.optString("id")
                    ?.let { droppedIds.add(it.lowercase(Locale.ROOT)) }
                continue
            }
            if (markOwnExtensionBuiltin(entry, dirName)) metadataUpdated = true
            kept.put(entry)
            entry.optJSONObject("identifier")?.optString("id")
                ?.let { keptIds.add(it.lowercase(Locale.ROOT)) }
        }

        // Built once so the identifier of each bundled directory is read
        // from its own package.json rather than guessed from the folder name.
        val bundledEntries = bundledDirs.mapNotNull { dirName ->
            manifestEntryFor(extensionsDir, dirName)?.let {
                it.getJSONObject("identifier").getString("id") to it
            }
        }

        val relist = bundledIdsToRelist(
            bundledIds = bundledEntries.map { it.first },
            keptIds = keptIds,
            droppedIds = droppedIds,
            previouslyBundledIds = previouslyBundledIds(),
        ).toSet()

        var added = 0
        for ((id, entry) in bundledEntries) {
            if (id !in relist) continue
            kept.put(entry)
            added++
        }

        if (dropped > 0 || added > 0 || metadataUpdated) {
            // Same exposure as settings.json above: a truncated manifest
            // is read as an empty extension list, so every bundled
            // extension disappears rather than the write visibly failing.
            if (!writeAtomically(manifestFile) { it.write(kept.toString(2).toByteArray()) }) {
                throw ManifestWriteFailed("could not rewrite $manifestFile")
            }
            Logger.i(tag, "Reconciled extensions.json: $dropped stale dropped, $added bundled added")
        }

        // Below the write, not above it. An earlier version sat above and
        // reasoned only about ordering against the DECISION -- correct as
        // far as it went, and the write six lines further down never
        // entered the frame. Recording a set the manifest does not contain
        // is the same defect [generateExtensionsManifest] throws to avoid,
        // and this is the half that runs on upgrades rather than fresh
        // installs: an identifier in the record with no entry beside it
        // reads as one the user removed, so it is never listed again, and
        // every later reconcile writes the bad set back over itself.
        //
        // The set is what this build BUNDLES, not what an entry could be
        // built for, and the two differ in exactly one place: a bundled
        // directory [bundledDirsToExtract] declined to unpack because the
        // user already holds a newer copy of the same id. That copy carries
        // the manifest entry, so the id is listed either way, and recording
        // it is what stops a later uninstall of it reading as an id this app
        // never shipped. Unconditional is still right for the same reason as
        // before: with nothing to write, the manifest on disk already agrees.
        rememberBundledIds(bundledIds)
    } catch (e: ManifestWriteFailed) {
        // Its own type, because IOException is too wide to mean "the write
        // failed" here. `manifestFile.readText()` above is inside this same
        // try and throws IOException too -- if the file is replaced by a
        // directory, or removed between the exists() check and the read --
        // and catching that would abort the whole setup over something the
        // catch below has always handled and survived. Only the write has
        // to reach runSetupLocked, because only the write leaves a record
        // that outlives the process.
        throw e
    } catch (e: Exception) {
        // A manifest this code cannot parse is one the server wrote in a
        // shape it understands; leave it alone rather than risk the user's
        // installed-extensions list.
        Logger.e(tag, "Could not reconcile extensions.json", e)
    }
}


/**
 * The manifest rewrite failed, as distinct from the manifest being unreadable.
 *
 * [FirstRunSetup.reconcileExtensionsManifest] answers those two differently: an
 * unparseable manifest is left alone and setup carries on, while a failed write
 * has to abort so the identifier record is not persisted ahead of a file that
 * does not contain it. Both arrive as `IOException` from inside one try, so the
 * distinction needs a type rather than a catch clause.
 */
internal class ManifestWriteFailed(message: String) : IOException(message)
