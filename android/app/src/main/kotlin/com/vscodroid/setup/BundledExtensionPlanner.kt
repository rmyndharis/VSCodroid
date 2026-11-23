package com.vscodroid.setup

import com.vscodroid.util.StorageManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.Locale

// Pure planning and version-comparison rules for bundled extensions, Python, ICU, and pip metadata.

/**
 * Directories under our own publisher that this build no longer bundles.
 *
 * `vscodroid.*` never appears on the marketplace, so such a directory can only
 * be a leftover from a previous build of this app; the github-auth stub is the
 * case that prompted this. With the manifest reconciled it would otherwise
 * survive forever: reconciliation keeps any entry whose directory exists. A
 * directory whose base id is still bundled is not retired; its versions belong
 * to [supersededExtensionDirs].
 */
internal fun retiredOwnExtensionDirs(present: List<String>, bundled: List<String>): List<String> {
    fun base(dir: String): String? {
        val cut = dir.lastIndexOf('-')
        if (cut <= 0 || cut == dir.length - 1) return null
        return dir.substring(0, cut)
    }

    val bundledBases = bundled.mapNotNull(::base).toSet()
    return present.filter { name ->
        name.startsWith(OWN_EXTENSION_PREFIX) &&
            name !in bundled &&
            base(name).let { it != null && it !in bundledBases }
    }
}


/**
 * Extension identifiers this project used to bundle and has stopped bundling.
 *
 * GitLens is here because it was bundled, it no longer is, and what earlier
 * releases unpacked is still on those devices -- roughly 22 MB that nothing
 * else will ever remove. `b73f558` stopped the BUNDLE, which is a different
 * thing from clearing what the bundle already installed.
 */
private val RETIRED_FETCHED_IDS = listOf("eamodio.gitlens")


/**
 * The third retirement case: an extension fetched from the marketplace that
 * this build no longer bundles, at whatever version it happens to be.
 *
 * Four sweeps now read the same directory and a reader has to know which one
 * claims a given name, so state it once:
 *
 *   [bundledDirsToExtract]      what to unpack -- ours always, theirs if absent
 *                               and not removed by the user
 *   [supersededExtensionDirs]   older versions of an id STILL bundled
 *   [retiredOwnExtensionDirs]   our own publisher, id no longer bundled
 *   this one                    a fetched id no longer bundled at all
 *
 * The first three all derive their answer from what IS bundled. This one
 * cannot: nothing in the current build mentions the extension, which is exactly
 * what makes it invisible to the other two, so the identifier is carried
 * explicitly.
 *
 * Matched on the identifier, not the version, so it does not matter which
 * release a device installed -- and the pin moved four times before removal, so
 * keying on a version would have cleared some devices and not others. The
 * directory is `id-version`, so the test is the id itself or the id followed by
 * a hyphen; a bare prefix would also claim a different extension whose name
 * merely begins the same way.
 */
internal fun retiredFetchedExtensionDirs(
    present: List<String>,
    retiredIds: List<String> = RETIRED_FETCHED_IDS,
): List<String> = present.filter { name ->
    !TARGET_PLATFORM_SUFFIX.containsMatchIn(name) &&
        retiredIds.any { id -> name == id || name.startsWith("$id-") }
}


/**
 * Which retired ids still have a sweep owed to them.
 *
 * The sweep exists to clear what an earlier bundle left behind, which is a
 * one-time debt against a device. It was running on every app update instead,
 * and it cannot tell a leftover from a deliberate reinstall: both are a
 * directory named `eamodio.gitlens-<version>`. So a user who liked the
 * extension, saw it vanish, and installed it again from the marketplace lost it
 * again on the next update, and every update after that.
 *
 * Recording the ids already swept turns it back into what it was meant to be.
 * A device that has swept before will sweep once more, because nothing recorded
 * the earlier runs and nothing can reconstruct them; after that it stops.
 */
internal fun retiredIdsToSweep(
    alreadySwept: Set<String>,
    retiredIds: List<String> = RETIRED_FETCHED_IDS,
): List<String> = retiredIds.filterNot { it in alreadySwept }


/**
 * The publisher this project ships extensions under.
 *
 * Shared by the two decisions that turn on authorship so they cannot drift
 * apart: [retiredOwnExtensionDirs], which may delete a directory carrying it,
 * and [bundledDirsToExtract], which re-unpacks one every time. It is safe to
 * key on because this publisher never appears on the marketplace, so a
 * directory carrying it can only have come from a build of this app.
 */
internal const val OWN_EXTENSION_PREFIX = "vscodroid."


/**
 * The file that says an extension directory is being unpacked right now.
 *
 * Written inside the directory before the copy starts and removed after it
 * lands, so that the directory means "unpacked" rather than "created". A dot
 * name because the directory is also an extension root: the scanner reads
 * `package.json` and nothing else, and a dot file is out of the way of anyone
 * listing it by hand.
 */
internal const val UNPACK_MARKER_NAME = ".vscodroid-unpacking"


/**
 * Whether the copy that created this extension directory never finished.
 *
 * Two things are asked, and the second is not redundant. The mark is the
 * reliable answer, but only for a directory created since it existed; an
 * install unpacked by an earlier release carries no mark whatever state it is
 * in. A fetched extension with no `package.json` is not loadable by anything --
 * `manifestEntryFor` declines it, the workbench's scanner cannot see it -- so
 * reading that as unfinished costs a copy the extension needed anyway, and
 * recovers the devices this app already broke rather than only the ones it has
 * yet to.
 *
 * Both directions matter. Answering yes for a healthy directory would re-copy
 * 57 MB on every upgrade and take with it whatever an extension has written
 * inside its own tree since install, which is the cost [bundledDirsToExtract]
 * exists to avoid; answering no for a wrecked one is the defect this closes.
 */
internal fun unpackWasAbandoned(dir: File): Boolean =
    File(dir, UNPACK_MARKER_NAME).isFile || !File(dir, "package.json").isFile


/**
 * Whether an unpack that failed has to remove the directory it was writing
 * into, for the next run to try again.
 *
 * The question is "would anything name this again", not "did this attempt
 * create it", and the two stopped being the same answer when [abandoned] began
 * selecting a fetched directory that is present. Three cases:
 *
 *  - a directory this attempt created goes. A fetched extension whose directory
 *    is merely present is dropped by [bundledDirsToExtract], and the half of it
 *    that landed is enough for `manifestEntryFor` to list it as an install.
 *  - one that was already there and carries the mark stays. [unpackWasAbandoned]
 *    reads that mark, so the name comes back on the list, and what is on disk is
 *    the previous release's install with part of this one merged over it: whole
 *    even if mixed, since every file is written through a rename.
 *  - one that was already there and carries NO mark stays only if it is ours.
 *    Ours are re-unpacked unconditionally, so the previous release's copy is
 *    worth more kept than deleted. A fetched one is not: it reached this loop
 *    because it read as abandoned, and if the mark could not be written while
 *    the manifest did land, nothing on the device would select it again --
 *    wreckage that lists itself as installed and is dead on every activation.
 *    Removing it puts it back in the ABSENT case the retry has always handled.
 *
 * Pure, and takes the three facts rather than a `File`, so every combination is
 * decidable without a tree.
 */
internal fun failedUnpackMustBeRemoved(
    name: String,
    existedBefore: Boolean,
    marked: Boolean,
): Boolean = !existedBefore || !(marked || name.startsWith(OWN_EXTENSION_PREFIX))


/**
 * Which bundled extension directories have to be unpacked over what is already
 * on disk.
 *
 * The split follows who wrote the extension, not how big it is, and that is the
 * whole of it: **content authored in this repository can change without its
 * version number moving; content fetched by version from Open VSX cannot.**
 * Collapsing the two branches back into one loses that distinction in whichever
 * direction it is collapsed.
 *
 * A directory is named `publisher.name-version`, so its presence answers "has a
 * directory carrying this version string been unpacked before" and nothing
 * about its contents. For an extension `download-extensions.sh` fetches at a
 * pinned version that is an exact staleness test -- the same version is the
 * same bytes -- **once the unpack that created it has finished**. It is created
 * before the first file is copied into it, so on its own it also answers yes
 * for a directory a process kill left half full, which is a state no sweep here
 * can reach and no later run would retry: that is what [abandoned] carries, and
 * a name in it is treated as though the directory were not there. A newer
 * install of the same id still wins over it, deliberately, since the copy the
 * user chose is the one that runs and unpacking beside it writes 29 MiB that
 * nothing loads. For one this repository edits in place presence is no test at all,
 * which is the defect that removed the check: the process-monitor extension's
 * code was rewritten while its `package.json` stayed at 1.0.0, so the fix
 * reached clean installs and no one who upgraded.
 *
 * Both kinds share `assets/extensions`, and `.gitignore` is where the line is
 * already drawn -- this project's own are kept under source control, everything
 * else is downloaded. [OWN_EXTENSION_PREFIX] is that line expressed in a form
 * available at runtime.
 *
 * What the split buys, beyond correctness. Ours are twelve files and tens of
 * KB, so re-unpacking them every time costs nothing. The fetched ones are 57 MB
 * across 3787 files -- `ms-python.python` alone is 29 MB, a figure this file
 * also records at [supersededExtensionDirs] -- and re-copying those on every
 * versionName change would sit behind a progress bar that does not move off 88.
 * It also means an extension that regenerates files inside its own directory at
 * runtime is never copied over, so that state survives an upgrade; a blanket
 * re-copy would silently revert it.
 *
 * A fetched extension is also skipped when the same or a NEWER version of that
 * identifier is already installed under another directory, or when ANY version
 * of it sits under a directory the gallery named, which is a user's own gallery
 * install of something this build bundles. The gallery names that
 * directory `<id>-<version>-<targetPlatform>`, which [splitExtensionDir]
 * reads. Nothing else would ever have removed it: [supersededExtensionDirs]
 * refuses to touch a directory that is currently bundled or that the gallery
 * named, [retiredOwnExtensionDirs] wants our publisher, and
 * [retiredFetchedExtensionDirs] wants an identifier this build no longer ships.
 * So it was unpacked, listed by nobody -- `bundledIdsToRelist` declines to add an
 * entry for an identifier whose own entry survives, deliberately, so the user's
 * newer install keeps winning -- and left on disk for good, 29 MiB for the
 * Python extension alone, re-created on every upgrade. Not unpacking it is the
 * whole remedy: the copy the user chose is the one that runs either way.
 *
 * A fetched extension is skipped for a second reason: the user removed it.
 * VS Code's uninstall deletes the directory and the manifest entry together,
 * so on the next upgrade the directory is absent and presence alone said
 * "unpack". [bundledIdsToRelist] then declined to list it, on purpose, so the
 * copy was dead on arrival and nothing swept it, since every sweep leaves a
 * name this build bundles alone. [uninstalled] carries the ids the caller
 * derived from the same two facts the relist reads (bundled before, listed by
 * nothing now), and it is keyed on the id rather than the version because the
 * relist is: a bump after the uninstall would be unpacked and unlisted just the
 * same. Ours are never in it, since they are re-unpacked unconditionally and
 * a removed one of ours is not a case this app has ever handled.
 *
 * Those two checks count the directories the default profile LISTS, passed as
 * [listed], and not everything on disk. Profiles share one extensions directory,
 * so a gallery install a named profile made, or a directory nothing lists, is on
 * disk without being a copy the default profile can run. Counting it left the
 * default profile without the new bundled copy, the superseded sweep then removed
 * the old one, and with its entry the extension was gone from the profile and
 * recorded as removed. Null means the manifest could not say, and every
 * directory counts as it did before the parameter existed.
 *
 * Pure, and takes listings rather than a directory, so the decision is
 * testable without a Context or a tree. `(present, bundled)` in that order, the
 * same as [supersededExtensionDirs] and [retiredOwnExtensionDirs]: all three
 * take two `List<String>` and a swap between them compiles in silence, so the
 * only protection is that there is nothing to remember. [abandoned],
 * [uninstalled] and [listed] are `Set`s rather than further lists of the same
 * type, so they cannot join that swap, and all are defaulted because a caller
 * with nothing to say about wreckage, removals or listings gets the behaviour
 * that was here before they existed.
 */
internal fun bundledDirsToExtract(
    present: List<String>,
    bundled: List<String>,
    abandoned: Set<String> = emptySet(),
    uninstalled: Set<String> = emptySet(),
    listed: Set<String>? = null,
): List<String> {
    // An abandoned directory is wreckage, not an install: it must not count as
    // a copy of its own version that makes unpacking it redundant. Nor does one
    // the default profile does not list, when that is known.
    val counted = present.filter { it !in abandoned && (listed == null || it in listed) }
    val installed = counted.mapNotNull(::splitExtensionDir)
    // The identifiers the user holds from the gallery, whatever the version, and
    // without case as [orphanedBundledCopies] compares them. A directory the
    // gallery named is the user's copy and the one that runs:
    // [bundledIdsToRelist] keeps its entry and adds none beside it, nothing
    // sweeps a gallery name ([supersededExtensionDirs]), and
    // [orphanedBundledCopies] removes an unpacked bundled copy that sits beside
    // a listed one. Unpacking next to an OLDER gallery install therefore wrote
    // 29 MiB for Python and deleted it again on every update.
    val uninstalledIds = uninstalled.mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
    val galleryIds = counted
        .filter { TARGET_PLATFORM_SUFFIX.containsMatchIn(it) }
        .mapNotNull(::splitExtensionDir)
        .map { it.first.lowercase(Locale.ROOT) }
        .toSet()
    return bundled.filter { dir ->
        if (dir.startsWith(OWN_EXTENSION_PREFIX)) return@filter true
        if (dir in present && dir !in abandoned) return@filter false
        val (id, version) = splitExtensionDir(dir) ?: return@filter true
        // Lowercased to meet the record, which manifestEntryFor builds from
        // the lowercased package.json halves; a real bundled publisher is PKief.
        val normalizedId = id.lowercase(Locale.ROOT)
        if (normalizedId in uninstalledIds) return@filter false
        if (normalizedId in galleryIds) return@filter false
        installed.none { (otherId, otherVersion) ->
            otherId.lowercase(Locale.ROOT) == normalizedId &&
                (otherVersion == version || isOlderVersion(version, otherVersion))
        }
    }
}


/**
 * The extension identifiers this build bundles, read from the directory names
 * in `assets/extensions` rather than from what reached the disk.
 *
 * The record these feed is the only thing that tells "the user uninstalled this
 * bundled extension" from "this build has never shipped it" (see
 * [bundledIdsToRelist]), so it has to name what the APK carries. Deriving it
 * from the directories instead named something narrower, the ids a manifest
 * entry could be built for, and the two were the same set only while every
 * bundled directory was unpacked. [bundledDirsToExtract] now skips a fetched
 * one whose id the user already holds at a newer version: that directory is
 * never created, `manifestEntryFor` answers null for it, its id fell out of the
 * record, and the user's later uninstall of their own copy then read as an id
 * this app had never bundled and was put back.
 *
 * The directory is `publisher.name-version` and the identifier is the same
 * publisher and name lowercased, which is what `manifestEntryFor` builds from
 * the `package.json` inside. That the two agree is measured rather than
 * assumed: `BundledExtensionVersionTest` fails the build when a bundled
 * directory and its own manifest disagree, and `release.yml` runs the unit
 * tests after `download-extensions.sh`, so the fetched directories are covered
 * as well as the committed ones.
 *
 * A name that is not that shape contributes nothing, which is the answer an
 * unreadable `package.json` already gave.
 */
internal fun bundledExtensionIds(bundled: List<String>): List<String> =
    bundled.mapNotNull { splitExtensionDir(it)?.first?.lowercase(Locale.ROOT) }


/**
 * Bundled copies an earlier update unpacked beside the user's own install of the
 * same identifier, which nothing lists and nothing loads.
 *
 * Until [bundledDirsToExtract] learned to read a gallery directory's name, every
 * update unpacked a fetched extension beside a newer copy the user had installed
 * from the gallery. [bundledIdsToRelist] keeps the user's entry and adds none for
 * the bundled copy, so it was never loaded, and every sweep leaves a directory
 * this build bundles alone, so it was never removed either: 29 MiB for Python.
 *
 * It deletes, so a name must pass every one of these, each of which is evidence
 * rather than an inference:
 *  - it is a directory this build bundles, exactly: never a gallery install,
 *    which carries a target platform in its name, and never our own publisher,
 *    whose extensions are always listed;
 *  - it is on disk, and neither the default profile's manifest nor any other
 *    profile's names it;
 *  - the default manifest lists the same identifier at another directory that
 *    is on disk NOW. [onDisk] has to be read after the superseded sweep, because
 *    a version bump leaves the new bundled copy unlisted for a moment too, while
 *    the entry for the version just swept still names the old directory; the
 *    reconcile that follows lists the new copy in its place.
 *
 * Identifiers are compared without case, as the manifest and the directory name
 * may spell a publisher differently.
 */
internal fun orphanedBundledCopies(
    onDisk: List<String>,
    bundled: List<String>,
    listed: List<Pair<String, String>>,
    referencedElsewhere: Set<String>,
): List<String> {
    val listedDirs = listed.map { it.second }.toSet()
    return bundled.filter { dir ->
        if (dir.startsWith(OWN_EXTENSION_PREFIX)) return@filter false
        if (dir !in onDisk || dir in listedDirs || dir in referencedElsewhere) return@filter false
        val id = splitExtensionDir(dir)?.first?.lowercase(Locale.ROOT) ?: return@filter false
        listed.any { (otherId, otherDir) ->
            otherId.lowercase(Locale.ROOT) == id && otherDir.isNotEmpty() && otherDir != dir && otherDir in onDisk
        }
    }
}


/**
 * Whether the setup recorded on this device belongs to a build other than the
 * one now running, and therefore has to be redone.
 *
 * Both halves of the identity are compared, and the versionCode is the half
 * that carries the guarantee. A versionName is a label a build declares about
 * itself and nothing stops two builds declaring the same one: 1.1.0 was
 * published under versionCode 11 and then again under 12. Comparing the label
 * alone, which is what this did, answers "same build" for those two -- so a
 * device holding the first takes the second, skips setup entirely, and keeps
 * running the older server tree under the newer app. Nothing reports it,
 * because from the app's side setup completed; it completed for a different
 * build.
 *
 * Play refuses an upload whose versionCode is not greater than the last, so the
 * code cannot repeat where the name can. Comparing both means the name stays
 * free to be whatever a release wants to call itself.
 *
 * A stored code of 0 is the value getInt returns when the
 * key was never written, which is any install predating the code being
 * recorded. Those are treated as stale, which redoes an extraction that is
 * idempotent, rather than trusting a record that was never made.
 *
 * Package replacement can retain both version labels, so a known current
 * timestamp must match the completed record. A legacy record with no timestamp
 * is refreshed once to record the current package identity. An unknown current
 * timestamp retains the version-based decision.
 */
internal fun setupIsStale(
    storedName: String?,
    storedCode: Int,
    currentName: String,
    currentCode: Int,
    storedUpdateTime: Long = 0,
    currentUpdateTime: Long = 0,
): Boolean = storedName != currentName || storedCode != currentCode ||
    (currentUpdateTime > 0 && storedUpdateTime != currentUpdateTime)


/**
 * Names the entries in `usr/lib` that belong to a Python the APK no longer
 * carries, given the runtime it does carry.
 *
 * Separated from the filesystem because the risk is one-directional and worth
 * testing on its own: naming one entry too few wastes disk, while naming one
 * too many deletes a stdlib that is still in use. Everything not recognisably
 * a Python runtime or stdlib is left alone, so an unfamiliar name in `usr/lib`
 * is never a candidate.
 *
 * @param present names in `usr/lib`, files and directories alike
 * @param runtime the `libpython3.X.so` this build ships
 */
internal fun supersededPythonEntries(present: List<String>, runtime: String): List<String> {
    val version = PYTHON_RUNTIME_NAME.find(runtime)?.groupValues?.get(1) ?: return emptyList()
    val currentStdlib = "python$version"
    return present.filter { name ->
        when {
            PYTHON_RUNTIME_NAME.matches(name) -> name != runtime
            PYTHON_STDLIB_NAME.matches(name) -> name != currentStdlib
            else -> false
        }
    }
}


// Anchored on purpose. The runtime is libpython3.<minor>.so and the stdlib is
// python3.<minor>; an unanchored match would also claim libpython3.<minor>.so.1.0
// and any directory that merely begins with the same letters. No minor version
// is written out here: `scripts/download-python.sh` resolves it from the Termux
// index at build time, so a figure in this comment names whatever shipped on the
// day it was typed and the tree has already moved past two of them.
internal val PYTHON_RUNTIME_NAME = Regex("""^libpython(3\.\d+)\.so$""")

internal val PYTHON_STDLIB_NAME = Regex("""^python3\.\d+$""")

internal val PIP_DIST_INFO = Regex("""^pip-\d[0-9A-Za-z._+]*\.dist-info$""")


// Anchored on purpose: only the soname file, `lib<name>.so.<major>`. The development link
// `libicuuc.so`, a full version such as `libicuuc.so.78.1`, a backup copy and a name that
// merely contains an ICU name are not matched, so none can be named below.
internal val ICU_LIBRARY_NAME = Regex("""^(libicu[a-z0-9]+)\.so\.(\d+)$""")


/**
 * Names the ICU libraries in `usr/lib` that an update replaced, given what the APK carries.
 *
 * ICU is versioned by its soname, and the runtime asks for exactly one major. The risk is
 * one-directional and worth testing on its own, as for [supersededPythonEntries]: naming one
 * too few wastes 38 MB, naming one too many deletes a library something loads. So a name is
 * a candidate only when the APK carries the same library under majors that do not include
 * it. A library the APK does not carry at all is never named, so an APK with no ICU names
 * nothing, and an APK that carries two majors of one library wants both, so only a third
 * is named.
 *
 * @param present names in `usr/lib`, files and directories alike
 * @param shipped names the APK carries under `usr/lib`
 */
internal fun supersededIcuEntries(present: List<String>, shipped: Collection<String>): List<String> {
    val carried = HashMap<String, MutableSet<String>>()
    for (name in shipped) {
        val (library, major) = ICU_LIBRARY_NAME.matchEntire(name)?.destructured ?: continue
        carried.getOrPut(library) { HashSet() }.add(major)
    }
    if (carried.isEmpty()) return emptyList()
    return present.filter { name ->
        val (library, major) = ICU_LIBRARY_NAME.matchEntire(name)?.destructured ?: return@filter false
        val majors = carried[library]
        majors != null && major !in majors
    }
}


/**
 * Names `pip-<version>.dist-info` directories in `site-packages` that do not
 * match the `pip-*.dist-info` shipped in the APK's assets.
 *
 * Fails closed: returns an empty list when [shipped] is null or contains no
 * `pip-*.dist-info` entry, or when the shipped entry is not present in
 * [present] (so an incomplete install never deletes the only metadata on disk).
 */
internal fun supersededBundledPipDistInfo(
    present: List<String>,
    shipped: Set<String>?,
): List<String> {
    val shippedPip = shipped?.filter { PIP_DIST_INFO.matches(it) }?.toSet() ?: return emptyList()
    if (shippedPip.isEmpty()) return emptyList()
    if (present.none { it in shippedPip }) return emptyList()
    return present.filter { PIP_DIST_INFO.matches(it) && it !in shippedPip }
}


/**
 * Which bundled extension identifiers need an entry written back into
 * `extensions.json`, given what the manifest already holds.
 *
 * Two cases arrive here looking exactly alike -- no manifest entry, and a
 * directory on disk with a `package.json` in it:
 *
 *  - the user uninstalled a bundled extension. VS Code removes the entry *and*
 *    the directory, so re-listing it would undo that choice on every upgrade.
 *    [bundledDirsToExtract] now declines to recreate that directory when the
 *    manifest can be read, so this arm is reached for the copy an earlier
 *    release already unpacked and left behind, and for a manifest that could
 *    not be read at extraction time.
 *  - the app began bundling an extension it has never shipped before.
 *
 * [previouslyBundledIds] is the only thing that separates them, which is why the
 * caller persists it. An identifier this app has never bundled cannot be one the
 * user removed. Without that record the first case was assumed for both, and
 * `vscodroid.vscodroid-serve-network` -- new in v1.1.0 -- reached no one
 * upgrading from v1.0.0 while working perfectly on a clean install, so no test
 * on a fresh device could have caught it.
 *
 * [keptIds] wins over every other reason to add: an identifier that still has a
 * live entry must not gain a second one, and a user's own newer install of the
 * same extension keeps its entry rather than being shadowed by the bundled copy.
 *
 * Pure, and takes the four sets rather than the files, so the decision is
 * testable without a manifest, a directory tree or a Context.
 */
internal fun bundledIdsToRelist(
    bundledIds: List<String>,
    keptIds: Set<String>,
    droppedIds: Set<String>,
    previouslyBundledIds: Set<String>,
): List<String> {
    val kept = keptIds.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
    val dropped = droppedIds.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
    val previous = previouslyBundledIds.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
    return bundledIds.filter { id ->
        val normalized = id.lowercase(Locale.ROOT)
        when {
            normalized in kept -> false
            // Its entry was just dropped because the directory it named is gone --
            // an upgrade swapping 1.0.0 for 1.3.0 does exactly this.
            normalized in dropped -> true
            // Ours, always, and this is the half that used to disagree with itself.
            // The rule below exists so a downloaded extension the user deliberately
            // removed does not come back on every update, and that promise is about
            // marketplace copies. [bundledDirsToExtract] already exempts this prefix
            // from exactly the same rule, so the directory returned on every update
            // and then sat unlisted and unloaded, which is the state the comment in
            // [manifestEntryFor] describes.
            //
            // These are not a removable preference: they carry the device folder
            // picker, the toolchain screen and the editor defaults this app
            // contributes, so a removed one takes those with it and nothing inside
            // the editor can put it back. The removal that reached here was possible
            // on releases that wrote no `isBuiltin`, and `isBuiltin` only stops the
            // next one; it cannot relist a copy already gone. Without this arm a user
            // who removed the welcome extension keeps the prune of the app's old
            // editor overrides, which commits once and for all, while the defaults
            // that replaced them never load.
            normalized.startsWith(OWN_EXTENSION_PREFIX) -> true
            // Never bundled before, so there was no copy for the user to remove.
            else -> normalized !in previous
        }
    }
}


/**
 * Removes an extension directory, unlinking links rather than following them,
 * and answers whether the path is gone.
 *
 * `File.deleteRecursively` asks `isDirectory` and `listFiles`, and both answer
 * for the target of a link, so it empties whatever a link inside the swept tree
 * points at before unlinking the link itself. The extensions directory is the
 * user's: it is what the server is given as `--extensions-dir`, it is
 * `~/.vscodroid/extensions` in the terminal, and `npm link` or a hand-made link
 * into a project is an ordinary thing to find under it. Removing a superseded
 * copy of a bundled extension then took the project's files with it. That is
 * the same defect [ToolchainManager] fixed for toolchain roots, so this uses the
 * same link-aware walk.
 *
 * The answer is "is it gone", not "did the walk report success": the walk
 * returns freed bytes, and a directory nothing could remove is exactly what the
 * callers want to log about.
 */
internal fun removeExtensionDir(dir: File, appRoot: File?): Boolean {
    StorageManager.deleteRecursive(dir, appRoot)
    return !dir.exists() && !Files.isSymbolicLink(dir.toPath())
}


/**
 * Names the extension directories left behind by an earlier bundled version.
 *
 * Bundled extensions are extracted to `publisher.name-version` directories, so
 * bumping a version extracts a new directory beside the old one. The scanner
 * shows only what `extensions.json` (the default profile's manifest) lists,
 * not what sits on disk, so the deletion here is half of the swap: it is what
 * lets reconcileExtensionsManifest drop the old entry and list the new version
 * in its place. The other stake is disk, which never comes back on its own:
 * the Python extension alone is 29 MB, kept for as long as the app is
 * installed. (An earlier version of this comment claimed the scanner discovers
 * extensions by listing directories; the manifest is what it reads.)
 *
 * Only strictly older copies are named. A user who installed a newer build of the
 * same extension from the marketplace keeps it: that is their copy, and the
 * scanner already prefers it. A version that is not purely numeric is left alone
 * rather than guessed at.
 */
internal fun supersededExtensionDirs(present: List<String>, bundled: List<String>): List<String> {
    val current = bundled.mapNotNull(::splitExtensionDir).toMap()
    return present.filter { name ->
        if (name in bundled) return@filter false
        // Named by the gallery, so the user's, never a leftover of ours.
        if (TARGET_PLATFORM_SUFFIX.containsMatchIn(name)) return@filter false
        val (id, version) = splitExtensionDir(name) ?: return@filter false
        val bundledVersion = current[id] ?: return@filter false
        isOlderVersion(version, bundledVersion)
    }
}


/**
 * A named profile's `extensions.json` with each entry for a superseded copy of one of OUR
 * extensions pointed at the copy this build bundles, or null when nothing changes or the
 * text is not a manifest.
 *
 * A profile made by copying the default one lists the copy of each extension that was
 * current then, and an update replaces the default profile's entries only. The superseded
 * sweep never removes a directory another profile lists, so the profile went on running
 * the old bridge and the old welcome page for good, without the Run button or any later
 * fix, with the old directory beside the new one. Our extensions are the app and not the
 * user's choice, so for them the entry follows the update; an older copy of anything else
 * a profile lists is the user's to keep and is returned as it was. Both are on disk
 * together at this point, which is why [bundled] is what is there and not what the APK
 * carries: an entry is never pointed at a directory that is missing.
 *
 * The entry is edited, not replaced: its version, the path of its location and, where it
 * has one, its relative location. The identifier and whatever the profile recorded beside
 * them stay. A profile that lists the new copy already loses the old entry instead, so
 * nothing is listed twice. Entries the reader cannot place are carried over unchanged.
 *
 * Pure, and takes the text rather than a file, so the decision is testable without a
 * Context or a tree; the caller writes the result atomically or not at all.
 */
internal fun repointOwnExtensionEntries(
    manifest: String,
    superseded: Collection<String>,
    bundled: Collection<String>,
    extensionsPath: String,
): String? {
    val entries = try {
        JSONArray(manifest)
    } catch (_: Exception) {
        return null
    }
    val current = bundled
        .filter { it.startsWith(OWN_EXTENSION_PREFIX) }
        .mapNotNull { dir -> splitExtensionDir(dir)?.let { (id, version) -> id to (dir to version) } }
        .toMap()

    // The directory an entry names, read the way manifestEntryDirs reads it.
    fun directoryOf(entry: JSONObject): String =
        entry.optString("relativeLocation").ifEmpty {
            val path = entry.optJSONObject("location")?.optString("path").orEmpty()
            if (path.isEmpty()) "" else File(path).name
        }

    val listed = (0 until entries.length())
        .mapNotNull { entries.optJSONObject(it)?.let(::directoryOf) }
        .toSet()
    val result = JSONArray()
    var changed = false
    for (i in 0 until entries.length()) {
        val entry = entries.optJSONObject(i)
        val directory = entry?.let(::directoryOf).orEmpty()
        if (entry != null && markOwnExtensionBuiltin(entry, directory)) changed = true
        val replacement = if (entry != null && directory in superseded && directory.startsWith(OWN_EXTENSION_PREFIX)) {
            splitExtensionDir(directory)?.first?.let(current::get)
        } else {
            null
        }
        when {
            entry == null || replacement == null -> result.put(entries.get(i))
            replacement.first in listed -> changed = true
            else -> {
                val (newDirectory, newVersion) = replacement
                entry.put("version", newVersion)
                entry.optJSONObject("location")?.put("path", File(extensionsPath, newDirectory).path)
                if (entry.has("relativeLocation")) entry.put("relativeLocation", newDirectory)
                result.put(entry)
                changed = true
            }
        }
    }
    return if (changed) result.toString(2) else null
}


internal fun markOwnExtensionBuiltin(entry: JSONObject, directory: String): Boolean {
    if (!directory.startsWith(OWN_EXTENSION_PREFIX)) return false
    val metadata = entry.optJSONObject("metadata")
    if (metadata?.optBoolean("isBuiltin", false) == true) return false
    if (metadata == null && entry.has("metadata") && !entry.isNull("metadata")) return false
    entry.put("metadata", (metadata ?: JSONObject()).put("isBuiltin", true))
    return true
}


/**
 * Splits a `publisher.name-version` directory name into its identifier and its
 * version, or null when it is not that shape.
 *
 * The last hyphen is the separator, because a publisher or a name may contain
 * one (`ms-python.python`) while a version may not. A directory the gallery
 * installed carries one more segment, the target platform upstream's
 * `ExtensionKey` appends (`ms-python.python-2026.5.0-universal`), and that
 * is dropped first; cutting at the last hyphen without doing so read the
 * platform as the version and the version as part of the identifier.
 *
 * Shared by the three decisions that compare versions of one identifier, so a
 * directory cannot be read one way by the sweep that removes it and another way
 * by the one that decides whether to unpack over it.
 */
internal fun splitExtensionDir(dir: String): Pair<String, String>? {
    val name = dir.replace(TARGET_PLATFORM_SUFFIX, "")
    val cut = name.lastIndexOf('-')
    if (cut <= 0 || cut == name.length - 1) return null
    return name.substring(0, cut) to name.substring(cut + 1)
}


/**
 * The target platforms upstream's `TargetPlatform` defines, as
 * `ExtensionKey.toString()` appends them to the directory of an extension the
 * gallery installed. `undefined` is never written, and nothing this app unpacks
 * carries any of them.
 */
private val TARGET_PLATFORM_SUFFIX = Regex(
    "-(?:win32-x64|win32-arm64|linux-x64|linux-arm64|linux-armhf|alpine-x64|alpine-arm64" +
        "|darwin-x64|darwin-arm64|web|universal|unknown)$"
)


/**
 * Whether [a] is a strictly older version than [b].
 *
 * False whenever either side is not purely numeric, so a pre-release or a
 * datestamped build nobody here can order is left alone rather than guessed at.
 */
private fun isOlderVersion(a: String, b: String): Boolean {
    fun parts(version: String): List<Int>? =
        version.split('.').map { it.toIntOrNull() ?: return null }

    val left = parts(a) ?: return false
    val right = parts(b) ?: return false
    for (i in 0 until maxOf(left.size, right.size)) {
        val l = left.getOrElse(i) { 0 }
        val r = right.getOrElse(i) { 0 }
        if (l != r) return l < r
    }
    return false
}
