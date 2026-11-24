package com.vscodroid.setup

import android.system.Os
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files

// Shell, TLS/CA, git, tool symlink, SSH, npm, pip, bashrc, tmux, and truncated-file repair configuration for [FirstRunSetup].

/**
 * Writes the OpenSSL configuration file `OPENSSL_CONF` names.
 *
 * The bundled libcrypto is Termux's build, and with no `OPENSSL_CONF` it
 * opens `/data/data/com.termux/files/usr/etc/tls/openssl.cnf`, the path
 * compiled into it. That is harmless on a device without Termux, where the
 * open fails with ENOENT and OpenSSL carries on. It is fatal on a device
 * where Termux has run: the directory belongs to another app, the open
 * fails with EACCES, and Node refuses to start ("OpenSSL configuration
 * error", before `main()`), six times over, so the user is told the server
 * crashed repeatedly. Read off the reporter's server.log in issue #447; no
 * emulator here had Termux on it, which is why it was never seen.
 *
 * The content is the smallest complete configuration: it names the default
 * provider, which is what OpenSSL uses when no file says otherwise, so
 * nothing changes for a process that was already starting. Rewritten
 * whenever the bytes differ rather than when the file is missing, because a
 * half-written file passes an existence check and fails the parser, which
 * Node treats exactly like the unreadable path this replaces.
 */
internal fun FirstRunSetup.setupOpensslConfigImpl() {
    val file = File(context.filesDir, "usr/etc/tls/openssl.cnf")
    if (file.isFile && runCatching { file.readText() }.getOrNull() == OPENSSL_CONF_CONTENT) return
    file.parentFile?.mkdirs()
    writeAtomically(file, { Logger.w(tag, it) }) { out ->
        out.write(OPENSSL_CONF_CONTENT.toByteArray())
    }
}


/**
 * Builds the single-file CA bundle git's curl insists on having.
 *
 * The bundled libcurl comes from Termux and carries Termux's compiled-in
 * bundle path, /data/data/com.termux/files/usr/etc/tls/cert.pem, which does
 * not exist in this sandbox. Every HTTPS request therefore died with "error
 * adding trust anchors from file", before any certificate was checked.
 *
 * Pointing GIT_SSL_CAPATH at Android's trust store does not answer it, and
 * that is the part worth recording: measured on device, a clone with CAPATH
 * set and nothing else still failed on the missing file, and clearing
 * sslCAInfo only changed the message to name an empty path. What works is a
 * bundle file -- with one, the clone succeeds whether CAPATH is set or not,
 * so curl was never reading the directory.
 *
 * The bundle is concatenated from the device's own trust store rather than
 * shipped, so it reflects what this device actually trusts and does not age
 * inside the APK. Two stores, not one: the system roots, and whatever CAs
 * the device owner installed for themselves through Settings. That second
 * half is why the bundle exists in the form it does. A developer whose
 * company re-signs TLS, or who runs an internal git host behind a CA they
 * issued, has already told the device to trust it, gone through the
 * full-screen warning and confirmed with their device credential; the
 * bundle honouring that is the app agreeing with a decision its owner
 * already made, not making one for them. Everything else here keeps system
 * trust: this writes the file git's curl reads and nothing more, so the
 * WebView and the toolchain downloader are untouched.
 *
 * The freshness test is two conditions rather than one, and the second is
 * not optional. Installing a CA through Settings writes into a store of its
 * own and never touches the mtime of the system certificate directory, so
 * the directory-newer-than-the-file check calls the bundle fresh at exactly
 * the moment it has gone stale. Without the fingerprint the right bundle
 * would be built once and then never again, and a user who installs a CA
 * would see the app ignore it for ever with nothing to indicate why. The
 * fingerprint covers the certificates' own bytes rather than their aliases
 * because a Conscrypt alias is a hash of the subject, so a CA re-issued
 * under the same name keeps the alias it had. It now covers the system half
 * as well, which is what lets a root the owner turned off rebuild the bundle:
 * turning one off writes into a store of its own and leaves the certificate
 * directory's mtime alone, exactly as installing one does.
 *
 * Built from the platform's merged store rather than from the certificate
 * directory plus the owner's additions. See [deviceTrustedCertificates] for
 * why: the directory keeps the file for a root the owner has distrusted, so
 * copying it kept trusting what the rest of the phone no longer does.
 */
internal fun FirstRunSetup.setupGitCaBundleImpl() {
    val bundle = File(context.filesDir, "usr/etc/tls/cert.pem")
    val marker = File(context.filesDir, "usr/etc/tls/.user-ca-fingerprint")
    try {
        val storePems = deviceCertificatePems()
        val caDir = systemCaCertificateDirs
            .map { File(it) }
            .firstOrNull { it.isDirectory }
        if (storePems == null && caDir == null) return
        // Include the source to invalidate older empty-store fallback bundles.
        val fingerprint = sha256HexOf(
            if (storePems == null) "system-directory-v2:${caDir?.path}"
            else "android-ca-store-v2:${storePems.joinToString("")}",
        )
        if (bundle.isFile && (bundle.length() > 0 || storePems?.isEmpty() == true) &&
            bundle.lastModified() >= (caDir?.lastModified() ?: 0) &&
            runCatching { marker.readText() }.getOrNull() == fingerprint
        ) {
            return
        }

        bundle.parentFile?.mkdirs()
        val certs = if (storePems == null) caDir?.listFiles()?.sortedBy { it.name } ?: return
            else emptyList()
        // Atomic, and the guard above is why. Writing straight to the final
        // path leaves a half-written bundle behind on any failure -- a full
        // disk, a kill mid-copy -- carrying a fresh mtime, and the freshness
        // check then returns early on every later launch. The result is a
        // permanently truncated trust store: some certificates present, the
        // rest missing, so HTTPS clones fail against whichever hosts fell on
        // the wrong side of the cut, with nothing to suggest the file is the
        // problem. An interrupted write now leaves the previous bundle, or
        // no bundle at all, and either one gets rebuilt next time.
        // A successful empty store is authoritative: copying raw roots would
        // restore trust the owner removed. Only an unavailable provider falls back.
        val written = writeAtomically(bundle) { out ->
            if (storePems != null) {
                for (pem in storePems) out.write(pem.toByteArray())
            } else {
                Logger.w(
                    tag,
                    "The device CA store is unavailable; falling back to " +
                        "${caDir?.path}, which cannot see a root the owner removed",
                )
                for (cert in certs) {
                    if (cert.isFile) cert.inputStream().use { it.copyTo(out) }
                }
            }
        }
        if (!written) {
            Logger.e(tag, "Could not write the CA bundle; the previous one is unchanged")
            return
        }
        // The marker goes last, after the bundle it vouches for is already
        // under its final name. Written first, a crash between the two would
        // strand a marker describing a bundle that was never built, and the
        // freshness check would then return early on every later launch --
        // the same permanent-staleness failure the atomic write above exists
        // to prevent, arriving by the other door. In this order a crash
        // anywhere leaves the marker absent or disagreeing, which is a
        // mismatch, which is a rebuild.
        if (!writeAtomically(marker) { it.write(fingerprint.toByteArray()) }) {
            Logger.w(tag, "Could not record the user-CA fingerprint; the bundle will be rebuilt next launch")
        }
        Logger.i(
            tag,
            if (storePems != null) {
                "CA bundle: ${storePems.size} certificates from the device trust store"
            } else {
                "CA bundle: ${certs.size} certificates from ${caDir?.path}, " +
                    "with no device trust store to read"
            },
        )
    } catch (e: Exception) {
        Logger.e(tag, "Failed to build CA bundle", e)
    }
}


/**
 * The device's merged trusted CAs, PEM-encoded and sorted.
 *
 * Sorted because [KeyStore.aliases] promises no order, and an order that
 * varies between launches would change the fingerprint without the trust
 * having changed, rebuilding the whole bundle on the main thread every time
 * the app starts.
 *
 * Null means the provider could not be read; an empty list means it trusts
 * no roots. Unencodable certificates are skipped while usable entries remain.
 * If none can be encoded, the caller preserves the previous bundle rather
 * than treating an encoding failure as empty trust or provider unavailability.
 *
 * The cost is paid on every launch and it is not free. Measured on an API 33
 * emulator with one CA installed, five launches: 28 ms in the steady state,
 * of which `KeyStore.aliases()` over 126 entries is 25 to 27 ms and the rest
 * is under a millisecond, against 45 ms for the whole repair block around
 * it. The first launch after an install costs 160 ms once.
 *
 * A cheaper signal was looked for and deliberately not taken. Measured from
 * inside this app on the same emulator, `/data/misc/user/0/cacerts-added` is
 * readable: `exists`, `isDirectory` and `Os.stat` all answer, and the
 * listing has the one entry it should. So an mtime on that directory could
 * skip the enumeration on the launches where nothing changed. It is not used
 * because it is a hardcoded platform path with a user id baked into it, it
 * would need the current user's id on a multi-user device, and no version of
 * it has been checked on Android 14 or later, where the root store moved
 * into an APEX. A path like that stops matching in silence, and the way it
 * fails here is the expensive direction: a directory that no longer exists
 * reads as "nothing changed". Not worth 28 ms on a screen whose next act is
 * to start a Node server. If the cost ever stops being affordable, move this
 * repair off the main thread beside the toolchain passes that already run
 * there, rather than weakening the fingerprint until the store's changes go
 * unnoticed again.
 */
internal fun FirstRunSetup.deviceCertificatePems(): List<String>? {
    val certs = runCatching { deviceTrustedCertificates() }
        .onFailure { Logger.w(tag, "Could not read the device CA store: ${it.message}") }
        .getOrNull() ?: return null
    val pems = certs
        .mapNotNull { cert -> runCatching { pemOf(cert) }.getOrNull() }
        .sorted()
    if (certs.isNotEmpty() && pems.isEmpty()) {
        throw IOException("No device CA certificate could be encoded")
    }
    return pems
}


/**
 * Points git-core's entries at binaries the app is actually allowed to run.
 *
 * Two kinds live there. Builtin subcommands are the same binary as git, so
 * they become symlinks to libgit.so. The remote helpers -- git-remote-http,
 * -https, -ftp and -ftps, one identical binary under four names -- are a
 * different program, shipped as libgit-remote-curl.so, and they are the ones
 * git genuinely has to exec: everything over HTTPS goes through one.
 *
 * Both have to resolve into nativeLibraryDir for the same reason ripgrep
 * does. SELinux refuses execve on anything under filesDir for targetSdk >=
 * 29, so the extracted copy of a helper is a file that installs, chmods and
 * then fails at the moment it is needed -- measured on an API 36 device,
 * "cannot exec 'git-remote-https': Permission denied" and a clone that dies
 * with "remote helper 'https' aborted session". A symlink works because
 * execve resolves it and checks the target.
 *
 * Links are repaired, not merely created, and that is the second half of the
 * fix. Android hands the app a new nativeLibraryDir on every reinstall, so
 * absolute links written by an earlier install point at a path that is gone
 * -- measured on the same device, where all 146 of them did, and where
 * git-clone reported "not found" rather than "permission denied" as a
 * result. File.exists() follows a link and reports false for exactly that
 * case, which is why staleness is read with Os.lstat and the recorded target
 * compared, the way setupToolSymlinks already does it.
 *
 * Safe to call on every launch, and called there so a reinstall cannot leave
 * git pointing into an install that no longer exists.
 */
internal fun FirstRunSetup.setupGitCoreImpl() {
    val config = File(context.filesDir, "usr/etc/gitconfig")
    // A file of NULs counts as empty (see isLeftBlank): git refuses a config it cannot parse, so every git
    // command failed. Whitespace does not: git reads it as a config with nothing in it, which may be chosen.
    if (!config.exists() || runCatching { config.readText().all { it == '\u0000' } }.getOrDefault(false)) {
        config.parentFile?.mkdirs()
        writeAtomically(config, { Logger.w(tag, it) }) { out ->
            out.write("[core]\n\tpager = cat\n".toByteArray())
        }
    }
    val nativeLibDir = context.applicationInfo.nativeLibraryDir
    val gitCorePath = File(context.filesDir, "usr/lib/git-core")
    val manifestFile = File(gitCorePath, "gitcore-symlinks")

    if (!manifestFile.exists()) {
        Logger.d(tag, "No gitcore-symlinks manifest found, skipping git-core setup")
        return
    }

    // git-core entry name -> the library in nativeLibraryDir it must resolve to
    val links = mutableMapOf<String, String>()
    manifestFile.readLines().filter { it.isNotBlank() }.forEach { links[it] = "libgit.so" }
    for (protocol in listOf("http", "https", "ftp", "ftps")) {
        links["git-remote-$protocol"] = "libgit-remote-curl.so"
    }

    var created = 0
    var repaired = 0

    for ((name, soName) in links) {
        val target = "$nativeLibDir/$soName"
        if (!File(target).exists()) continue
        val link = File(gitCorePath, name)

        val present = try { Os.lstat(link.absolutePath); true } catch (e: Exception) { false }
        if (present) {
            // readlink throws on a regular file, which is what the extracted
            // copy of a remote helper is -- that too has to be replaced.
            val current = try { Os.readlink(link.absolutePath) } catch (e: Exception) { null }
            if (current == target) continue
            if (!unlinkStale(link, "git-core link $name")) continue
            repaired++
        }

        try {
            Os.symlink(target, link.absolutePath)
            if (!present) created++
        } catch (e: Exception) {
            Logger.d(tag, "Failed to link $name -> $soName: ${e.message}")
        }
    }

    // The execute bit for the entries that are real extracted files: the
    // shell helpers (git-submodule, git-mergetool, git-sh-setup) and the
    // handful of standalone binaries git execs by name.
    //
    // Links are skipped, and not for tidiness. `File.isFile` follows one, so
    // this chmod'ed the target inside nativeLibraryDir instead, which SELinux
    // refuses the app: `avc: denied { setattr } ... tcontext=...
    // apk_data_file` on every cold start, for a bit those files already
    // carry. The result was discarded too, so nothing said the call had done
    // nothing. What it does for the real files is load-bearing, which is why
    // the loop stays rather than going.
    //
    // Asked through `Files.isSymbolicLink` rather than [isSymlink] for the
    // reason `installedExtractionBytes` gives: both are an lstat on the final
    // component, but `Os.lstat` throws in a JVM unit test and [isSymlink]
    // catches that into "not a link", so the skip could be asserted and never
    // measured.
    gitCorePath.listFiles()?.forEach { file ->
        if (file.isFile && !file.name.startsWith(".") && !Files.isSymbolicLink(file.toPath())) {
            if (!file.setExecutable(true, true)) {
                Logger.w(tag, "Could not set the execute bit on ${file.name}")
            }
        }
    }

    Logger.i(tag, "git-core: $created links created, $repaired repaired")
}


/**
 * Removes a link that has to be replaced, and says so when it cannot.
 *
 * @return true when the path is gone and the caller may write the new link.
 *
 * All five sites in this file that replace a link discarded the answer, and
 * failed the same way: a refused delete fell through to `Os.symlink`, came
 * back EEXIST, and left a `Logger.d`, which is gated on a debuggable build
 * and therefore absent from every release install. What was left behind is a
 * link still pointing where it pointed before -- a tool missing from PATH,
 * git unable to fetch over HTTPS, the Copilot SDK resolving an alias to
 * nothing, or a terminal starting in a directory that has moved -- with no
 * record anywhere that the repair meant to fix it had been refused.
 *
 * [what] names the link rather than the exception, because there is no
 * exception: `File.delete()` reports refusal by returning false.
 */
internal fun FirstRunSetup.unlinkStale(link: File, what: String): Boolean {
    if (link.delete()) return true
    Logger.w(tag, "Could not remove the stale $what at ${link.absolutePath}; it keeps its old target")
    return false
}


/**
 * Creates or updates symlinks in usr/bin/ pointing to native binaries.
 *
 * Android changes the nativeLibraryDir path on every reinstall (random hash),
 * so existing symlinks may point to a stale path. This method validates and
 * recreates them as needed, safe to call on every launch, not just first run.
 */
internal fun FirstRunSetup.setupToolSymlinksImpl() {
    val nativeLibDir = context.applicationInfo.nativeLibraryDir
    val binDir = File(context.filesDir, "usr/bin")
    binDir.mkdirs()

    val tools = mapOf(
        "bash" to "libbash.so",
        "git" to "libgit.so",
        "node" to "libnode.so",
        "python3" to "libpython.so",
        "python" to "libpython.so",
        "rg" to "libripgrep.so",
        "tmux" to "libtmux.so",
        "make" to "libmake.so",
        "ssh" to "libssh.so",
        "ssh-keygen" to "libssh-keygen.so",
        "scp" to "libscp.so",
        "sftp" to "libsftp.so",
    )

    var created = 0
    var updated = 0
    for ((name, soName) in tools) {
        var linkUpdated = false
        val link = File(binDir, name)
        val target = "$nativeLibDir/$soName"
        if (!File(target).exists()) continue

        // Check if a symlink already exists (lstat doesn't follow symlinks,
        // unlike File.exists() which returns false for dangling symlinks)
        val linkExists = try { Os.lstat(link.absolutePath); true } catch (e: Exception) { false }

        if (linkExists) {
            try {
                val currentTarget = Os.readlink(link.absolutePath)
                if (currentTarget == target) continue
            } catch (_: Exception) { }
            // Stale or broken symlink, remove it
            if (!unlinkStale(link, "tool symlink $name")) continue
            updated++
            linkUpdated = true
        }

        try {
            Os.symlink(target, link.absolutePath)
            if (!linkUpdated) created++
        } catch (e: Exception) {
            Logger.d(tag, "Failed to create symlink $name -> $soName: ${e.message}")
        }
    }
    Logger.i(tag, "Tool symlinks: $created created, $updated updated in usr/bin/")
}


/**
 * Creates a symlink so VS Code's ripgrep finds rg at its expected path.
 *
 * The binary lives in nativeLibraryDir as libripgrep.so, because SELinux will
 * not exec anything under filesDir. VS Code looks for it inside the server
 * tree instead, so the two are joined by a symlink -- execve resolves it and
 * checks the target, which is where execution is allowed.
 *
 * The path it looks in moved in VS Code 1.133: @vscode/ripgrep with a single
 * bin/rg became @vscode/ripgrep-universal with one directory per platform.
 * Both are linked, so an install carrying either tree finds it; the older one
 * costs a directory and a symlink.
 *
 * Safe to call on every launch (recreates if stale, skips if current).
 */
internal fun FirstRunSetup.setupRipgrepVscodeSymlinkImpl() {
    val nativeLibDir = context.applicationInfo.nativeLibraryDir
    val rgBinary = File("$nativeLibDir/libripgrep.so")
    if (!rgBinary.exists()) return

    val target = rgBinary.absolutePath
    val serverDir = File(context.filesDir, "server/vscode-reh/node_modules")
    val binDirs = listOf(
        File(serverDir, "@vscode/ripgrep-universal/bin/linux-arm64"),
        File(serverDir, "@vscode/ripgrep/bin"),
    )

    for (rgBinDir in binDirs) {
        rgBinDir.mkdirs()
        val rgLink = File(rgBinDir, "rg")

        val linkExists = try { Os.lstat(rgLink.absolutePath); true } catch (e: Exception) { false }
        if (linkExists) {
            try {
                if (Os.readlink(rgLink.absolutePath) == target) continue
            } catch (_: Exception) { }
            if (!unlinkStale(rgLink, "ripgrep symlink")) continue
        }

        try {
            Os.symlink(target, rgLink.absolutePath)
            Logger.i(tag, "ripgrep symlink: ${rgLink.absolutePath} -> $target")
        } catch (e: Exception) {
            Logger.d(tag, "Failed to create ripgrep symlink: ${e.message}")
        }
    }
}


/**
 * Aliases Copilot's platform-named paths under the name Android resolves.
 *
 * Node here reports process.platform === "android", and Copilot builds
 * package and directory names from it with no fallback, so what the server
 * tree ships for linux-arm64 is invisible on device under its own name. The
 * server tarball cannot carry these aliases itself because AAPT flattens
 * asset symlinks into copies, so like the tool symlinks above they are
 * rebuilt on every launch. Relative targets keep them valid across
 * reinstalls. Two sites:
 *
 *  - the built-in extension's node_modules: sdk -> ../copilot/sdk, whose
 *    index.js patch 0010 keeps in the build; the manifest pins the version
 *    the extension was compiled against. What resolves the name from there
 *    is the SDK the CLI package carries for its own extensions
 *    (`copilot-sdk/`, `getBundledCliPath`).
 *  - ripgrep-universal: bin/android-arm64 -> linux-arm64, whose rg is
 *    already the Bionic binary via setupRipgrepVscodeSymlink(). The
 *    extension's ripgrep shim copies from bin/<platform>-<arch>.
 *
 * There was a third, a link farm over the server's copilot-linux-arm64 for
 * the agent host. Patch 0020 keeps that host from starting here and the
 * tree stopped shipping the package with Code - OSS 1.139, so the farm is
 * gone, and an upgrade removes the one an earlier release left
 * ([pruneUnshippedServerEntries] from [runPreExtractionMigrations]).
 */
internal fun FirstRunSetup.setupCopilotAndroidAliasesImpl() {
    val serverRoot = File(context.filesDir, "server/vscode-reh")

    /**
     * Creates the alias, and repoints one whose target has moved.
     *
     * Presence was the whole test, which is what its three siblings
     * (setupToolSymlinks, setupGitCore, setupRipgrepVscodeSymlink) all
     * readlink and compare precisely to avoid. Nothing rewrites this
     * directory: it is built at runtime under the server tree rather than
     * carried in the assets, so extraction never touches it and only the
     * pre-pivot migration ever removed it. An alias written by an earlier
     * release, or one whose upstream entry was renamed, therefore kept
     * pointing where it always had for the life of the install.
     *
     * A path that is present and is NOT a symlink is left alone rather than
     * deleted: readlink answering nothing means a real file or directory,
     * which is not ours to remove on a guess.
     */
    fun linkTo(link: File, target: String) {
        val present = try { Os.lstat(link.absolutePath); true } catch (e: Exception) { false }
        if (present) {
            val current = runCatching { Os.readlink(link.absolutePath) }.getOrNull()
            if (current == null || current == target) return
            if (!unlinkStale(link, "Copilot platform alias ${link.name}")) return
        }
        try {
            Os.symlink(target, link.absolutePath)
        } catch (e: Exception) {
            Logger.d(tag, "copilot alias symlink failed for ${link.name}: ${e.message}")
        }
    }

    // Only meaningful when the tree keeps sdk/index.js (patch 0010); without
    // it the alias would resolve to a directory with no entry point.
    val extCopilot = File(serverRoot, "extensions/copilot/node_modules/@github/copilot")
    if (File(extCopilot, "sdk/index.js").exists()) {
        try {
            val version = org.json.JSONObject(File(extCopilot, "package.json").readText())
                .getString("version")
            val alias = File(extCopilot.parentFile, "copilot-android-arm64")
            alias.mkdirs()
            linkTo(File(alias, "sdk"), "../copilot/sdk")
            val manifest = """{"name":"@github/copilot-android-arm64","version":"$version","type":"module","exports":{"./sdk":{"import":"./sdk/index.js"}}}"""
            val aliasManifest = File(alias, "package.json")
            if (!aliasManifest.exists() || aliasManifest.readText() != manifest) {
                aliasManifest.writeText(manifest)
                Logger.i(tag, "copilot alias: extension copilot-android-arm64 pinned to $version")
            }
        } catch (e: Exception) {
            Logger.d(tag, "copilot extension alias failed: ${e.message}")
        }
    }

    // ripgrep-universal directory alias; its rg is the Bionic symlink.
    val rgBin = File(serverRoot, "node_modules/@vscode/ripgrep-universal/bin")
    if (File(rgBin, "linux-arm64").isDirectory) {
        linkTo(File(rgBin, "android-arm64"), "linux-arm64")
    }
}


/**
 * Creates default SSH configuration for git operations.
 *
 * Sets up ~/.ssh/ directory, default ssh_config (auto-accept first connection,
 * ed25519 key, keepalive), and correct file permissions. Runs from setup and does
 * not overwrite existing user SSH config; a link whose file has gone is also
 * written through at launch ([repairTruncatedSetupFiles]).
 */
internal fun FirstRunSetup.setupSshDefaultsImpl() {
    val homeDir = context.filesDir.absolutePath + "/home"
    val sshDir = File(homeDir, ".ssh")
    sshDir.mkdirs()

    // Set directory permissions to 700 (owner only)
    try {
        Os.chmod(sshDir.absolutePath, 448) // 0700 octal = 448 decimal
    } catch (e: Exception) {
        Logger.d(tag, "Failed to chmod .ssh: ${e.message}")
    }

    val sshConfig = File(sshDir, "config")
    val configured = holdingWriteLock(sshConfig) {
        if (!sshConfig.exists()) {
            // Atomic and loud, the pair createBashProfile is, and this was the
            // last exists()-guarded writer in the file still using writeText.
            // writeText creates and truncates before writing a byte, so a kill or
            // a full disk here left an empty config that satisfies the guard
            // above for ever, on a writer runSetupLocked reaches only when the
            // version moves and that repairTruncatedSetupFiles does not cover.
            // What that costs is every clone, fetch and push over ssh: an empty
            // config is still readable, so GIT_SSH_COMMAND passes it with -F, and
            // without its absolute IdentityFile and UserKnownHostsFile the Termux
            // ssh resolves ~ to its own compiled-in prefix, which is not a path
            // this app can read.
            // Through a link, as createBashrc writes: a config kept in a dotfiles
            // folder and linked here was replaced by a copy, which ssh then read
            // while the person's own file came back beside it unread.
            if (!writeSshConfig()) {
                // Not thrown for a link into a folder that has gone, for the reason createBashrc gives.
                if (!Files.isSymbolicLink(sshConfig.toPath())) throw IOException("could not write $sshConfig")
                Logger.w(tag, linkedFileUnwritten(sshConfig))
                return@holdingWriteLock false
            }
        }
        true
    }
    if (!configured) return

    Logger.i(tag, "SSH defaults configured")
}


/**
 * Writes the ssh config this app owns, through a link at the path, and makes it the owner's alone.
 * The guardless half of [setupSshDefaults], as [writeBashrc] is of [createBashrc].
 *
 * Absolute paths, from [Environment.sshDefaults], because Termux openssh resolves ~ to its compiled-in
 * prefix (/data/data/com.termux/...), not $HOME.
 */
internal fun FirstRunSetup.writeSshConfig(): Boolean {
    val sshConfig = File(context.filesDir, "home/.ssh/config")
    val content = "Host *\n" + Environment.sshDefaults(context.filesDir.absolutePath + "/home")
        .joinToString("") { (key, value) -> "    $key $value\n" }
    if (!writeAtomically(sshConfig, throughLink = true) { it.write(content.toByteArray()) }) return false
    try {
        Os.chmod(sshConfig.absolutePath, 384) // 0600, of the file a link names when it is one
    } catch (e: Exception) {
        Logger.d(tag, "Failed to chmod ssh config: ${e.message}")
    }
    return true
}


/**
 * Ensures npm/npx shell functions exist in .bashrc and creates .npmrc.
 *
 * SELinux denies app_data_file:file execute_no_trans for targetSdk >= 29, so a
 * script with a shebang under filesDir fails with "bad interpreter: Permission
 * denied" no matter how it is chmod'ed. Instead, npm/npx are defined as bash
 * functions that invoke node with the cli entry point.
 *
 * A caller that is not bash gets the same command from the launcher scripts
 * `ToolchainManager.addNpmRows` writes for the exec table, so a change to what
 * these functions run belongs there as well.
 *
 * Safe to call on every launch: only appends if functions are missing.
 */
internal fun FirstRunSetup.createNpmWrappersImpl() {
    val nativeLibDir = context.applicationInfo.nativeLibraryDir
    val filesDir = context.filesDir.absolutePath
    val npmCliJs = "$filesDir/usr/lib/node_modules/npm/bin/npm-cli.js"

    // Only set up if npm was actually extracted
    if (!File(npmCliJs).exists()) {
        Logger.d(tag, "npm not extracted yet, skipping npm setup")
        return
    }

    // Remove stale script-based wrappers from previous versions
    val binDir = File(context.filesDir, "usr/bin")
    for (name in listOf("npm", "npx")) {
        val script = File(binDir, name)
        if (script.exists() && !isSymlink(script)) {
            script.delete()
            Logger.d(tag, "Removed stale $name script wrapper")
        }
    }

    // Append npm/npx functions to .bashrc if not already present
    val bashrc = File(context.filesDir, "home/.bashrc")
    holdingWriteLock(bashrc) {
        if (!bashrc.exists()) return@holdingWriteLock
        // Kept as bytes and written back unchanged. Decoding to search for
        // the guards is fine -- they are ASCII -- but decoding to REWRITE
        // is not: readText replaces any byte that is not valid UTF-8 with
        // U+FFFD, so a Latin-1 accent the user typed into a comment or an
        // alias would be destroyed by appending an unrelated line. This
        // replaced appendText, which never read the file at all, and
        // carrying the bytes through is what keeps that property.
        val existing = bashrc.readBytes()
        val original = String(existing, Charsets.ISO_8859_1)
        val content = original.replace(legacyClaudeBlock, claudeBashFunction())
            .replace(legacyClaudeBlockFrom110, claudeBashFunction())
        val additions = StringBuilder()
        val added = mutableListOf<String>()
        if (content != original) added += "claude"
        // [npmBlockMarker], not `npm()`, and the difference is what lets this
        // block ever change again: every install that already has the pair
        // matches `npm()`, so that guard froze whatever the release which
        // first wrote the file happened to say.
        if (!content.contains(npmBlockMarker)) {
            additions.append(npmBashFunctions())
            added += "npm/npx"
        }
        // Migrate only the exact older block; keep any custom function.
        if (!CLAUDE_DEFINITION.containsMatchIn(content)) {
            additions.append(claudeBashFunction())
            added += "claude"
        }
        // Its own guard, for the reason the claude block states: every
        // install that predates this already carries npm() and claude(), so
        // sharing either guard would skip pip on exactly the devices that
        // need it added. [pipBlockMarker] and not `pip()`, for the reason
        // [npmBlockMarker] is not `npm()`: this block had frozen once
        // already. Every block this app has written opens with
        // [PIP_BLOCK_HEADER] and defines one `pip()`, so a `pip()` beyond
        // that count is the user's, and appending after it would replace
        // it, since bash takes the last definition.
        val ourPipBlocks = PIP_BLOCK_HEADER.toRegex(RegexOption.LITERAL).findAll(content).count()
        val theirOwnPip = PIP_DEFINITION.findAll(content).count() > ourPipBlocks ||
            PIP3_DEFINITION.findAll(content).count() > ourPipBlocks
        if (!content.contains(pipBlockMarker) && !theirOwnPip) {
            additions.append(pipBashFunctions())
            added += "pip/pip3"
        }
        // Each OpenSSH command block opens with its own header and defines one
        // function, so a definition beyond that count is the user's, and appending
        // after it would replace it, since bash takes the last one. Guarding
        // ssh, scp and sftp independently keeps any user override while letting
        // installs that already have ssh pick up scp and sftp on upgrade.
        for ((name, header, pattern, block) in openSshBashFunctions()) {
            val ourBlocks = header.toRegex(RegexOption.LITERAL).findAll(content).count()
            val theirOwn = pattern.findAll(content).count() > ourBlocks
            if (ourBlocks == 0 && !theirOwn) {
                additions.append(block)
                added += name
            }
        }
        // One rewrite through a temporary file rather than an append per
        // block. A guard that reads a string its own block opens with
        // certifies a partial append: `claude()` is there, its body is not,
        // and no later launch adds the rest. The two decisions stay
        // separate; only the write is shared.
        if (content != original || additions.isNotEmpty()) {
            val names = added.joinToString(" and ")
            if (writeAtomically(bashrc, throughLink = true) {
                it.write(content.toByteArray(Charsets.ISO_8859_1))
                it.write(additions.toString().toByteArray())
            }) {
                Logger.i(tag, "Updated $names in .bashrc")
            } else {
                Logger.w(tag, "Could not update $names; .bashrc is unchanged")
            }
        }
    }

    // Update .npmrc on every launch: nativeLibDir changes on APK reinstall.
    //
    // A `.npmrc` that is a link is a file the person keeps elsewhere, often in a repository
    // other machines read, and the two lines written here are this device's alone: a bash
    // under this install's library directory, and android as the platform. Such a file is
    // left with the person's own lines only, and the app's go into npm's global file
    // instead, which npm finds under NPM_CONFIG_PREFIX and lets the person's own file override.
    val ownNpmrc = File(context.filesDir, "home/.npmrc")
    val linked = Files.isSymbolicLink(ownNpmrc.toPath())
    val npmrc = if (linked) File(context.filesDir, "usr/etc/npmrc") else ownNpmrc
    val bashPath = "$nativeLibDir/libbash.so"
    // script-shell: use bundled bash for npm lifecycle scripts, because
    // npm's fallback is a POSIX shell and this app's scripts assume bash
    // os: the platform optional dependencies are picked for, so packages
    // that publish an android build, @rollup/rollup-android-arm64, get it
    //
    // Reconciled rather than rewritten, and that is the whole point of the
    // shape below. This used to build the three lines and write them over
    // whatever was there, on every launch. `.npmrc` is also where npm itself
    // writes: `npm config set registry`, an auth token for a private
    // registry, `cafile`, `strict-ssl`. All of it worked until the app was
    // next opened, and then it was gone, with nothing on screen and nothing
    // in the log to say the app had done it. A user who lost a token twice
    // would have no way to reach that conclusion.
    //
    // So only the two keys this app owns are replaced. `script-shell` is
    // owned because it has to track nativeLibraryDir, which Android moves on
    // every reinstall, and because npm's fallback is a POSIX shell while this
    // app's scripts assume bash. `os=android` is owned because npm itself runs
    // with process.platform reading "linux" (VSCODROID_PLATFORM_FIX in
    // [npmBashFunctions]), and with no `os` set that is the platform it picks
    // optional dependencies for. Rollup, rolldown and lightningcss ship their
    // native part as android-arm64 packages, and Vite stops at "Cannot find
    // native binding" without them. Every other line is carried through
    // untouched, in the order it was written.
    //
    // One value, because `os` is one string to npm. This used to write
    // `os[]=linux` and `os[]=android`, meant as both, which npm reads as an
    // array that npm-install-checks compares with each entry of a package's
    // `os` list by `===`. An array equals no entry, so every optional package
    // whose `os` list names a platform it supports was skipped, the android
    // ones included, and only a list that merely excludes platforms let a
    // package through: measured with npm 10.8.2, and 11.16.0 has the same
    // check. Those two lines stay owned so an upgrade removes them, since npm
    // appends a plain `os=` to an array an `os[]=` opened.
    //
    // The owned lines go first, and for `os` the order matters: npm takes the
    // last of a plain key given twice, so an `os=` the user set with
    // `npm config set` is carried through below this one and still decides.
    // That is also why only the exact line this app writes is owned.
    val ownedLine = Regex("""^\s*(script-shell\s*=|os\s*=\s*android\s*$|os\[]\s*=\s*(linux|android)\s*$)""")
    // Latin-1, not UTF-8, and the charset is load-bearing for the reason
    // [ensurePromptFix] gives: it maps all 256 byte values one to one, so
    // every byte read here comes back out unchanged. `.npmrc` is where npm
    // keeps a registry auth token, a proxy password and a `cafile` path, none
    // of which is required to be valid UTF-8, and `readText` replaces every
    // byte that is not with U+FFFD. That would destroy the credential this
    // carry-through exists to preserve, on the first launch after the user
    // set it, with nothing on screen and nothing in the log to say so. The
    // owned lines are ASCII, so encoding the result back through the same
    // mapping is lossless as well.
    val written = holdingWriteLock(npmrc, throughLink = false) {
        val existing = if (npmrc.exists()) String(npmrc.readBytes(), Charsets.ISO_8859_1) else ""
        val carriedOver = existing
            .lines()
            .filterNot { ownedLine.containsMatchIn(it) }
            .dropLastWhile { it.isBlank() }
        val expectedContent =
            (listOf("script-shell=$bashPath", "os=android") + carriedOver)
                .joinToString("\n", postfix = "\n")
        // Compared against that same decoding rather than a second `readText`.
        // A file holding one byte that is not valid UTF-8 would never compare
        // equal to what was just written to it, so every launch would rewrite an
        // already-correct file for ever.
        if (existing == expectedContent) return@holdingWriteLock true
        // Atomically, like every other setup file this class writes. This one
        // was the exception, and it is a bad one to be: `writeText` truncates
        // before it writes, so a write that runs out of disk leaves an empty
        // `.npmrc` rather than the previous one. Empty means no
        // `script-shell`, and npm then hands every lifecycle script to
        // `/bin/sh`. That path does exist on Android, as a symlink into
        // `/system/bin` -- measured on an API 36 emulator, `ls -l /bin/sh`
        // and `/system/bin/sh` are the same 312024-byte mksh. So the failure
        // is not ENOENT but a shell that is not bash: a postinstall using
        // `[[`, arrays or `source` dies with a syntax error, for a reason
        // nothing on screen connects to storage.
        //
        // Nothing repairs it either: repairTruncatedSetupFiles covers
        // `.bashrc` and `settings.json`, and an empty `.npmrc` is
        // indistinguishable from one a user emptied on purpose. The rewrite
        // above is reached only when the content differs, and an empty file
        // does differ, but only on a launch that has room, which is the
        // launch that would not have broken it.
        npmrc.parentFile?.mkdirs()
        val ok = writeAtomically(npmrc) { it.write(expectedContent.toByteArray(Charsets.ISO_8859_1)) }
        if (ok) {
            Logger.d(tag, "Updated ${npmrc.name}")
        } else {
            Logger.w(tag, "Could not update ${npmrc.name}; it keeps whatever it held before")
        }
        ok
    }
    // Only once the global file holds the lines: until then the linked file's are all npm has.
    if (linked && written) dropDeviceLinesFromLinkedNpmrc(ownNpmrc)
}


/**
 * Takes this app's own lines out of a `.npmrc` that is a link, through the link.
 *
 * The linked file can hold them: the app wrote them into `~/.npmrc` and the person then moved
 * that file into a repository and linked it back. npm reads a user file before the global one,
 * so a script shell left there from before an update named a bash that is gone and every npm
 * script failed with ENOENT, and on another machine the two lines name a path and a platform
 * that are not that machine's. Only the exact shapes this app writes go ([DEVICE_NPMRC_LINE]):
 * a script shell or an `os` the person set stays and still overrides the global file.
 */
internal fun FirstRunSetup.dropDeviceLinesFromLinkedNpmrc(link: File) {
    holdingWriteLock(link) {
        // Latin-1 and a split on the line feed alone, so every other byte comes back as it was.
        val lines = try {
            String(link.readBytes(), Charsets.ISO_8859_1).split("\n")
        } catch (_: IOException) {
            return@holdingWriteLock // A link to nothing.
        }
        val kept = lines.filterNot { DEVICE_NPMRC_LINE.containsMatchIn(it) }
        if (kept.size == lines.size) return@holdingWriteLock
        if (writeAtomically(link, throughLink = true) { it.write(kept.joinToString("\n").toByteArray(Charsets.ISO_8859_1)) }) {
            Logger.i(tag, "Took this device's lines out of the linked .npmrc; npm's global file holds them")
        } else {
            Logger.w(tag, "Could not take this device's lines out of the linked .npmrc; npm reads them before the global file")
        }
    }
}


/**
 * Clears a setup file an older release left half-written, so the writers
 * that skip it because it exists will write it again.
 *
 * Every writer of `.bashrc` and `settings.json` decides by asking whether
 * the file is there. Before those writes were made atomic, one interrupted
 * by a full disk left a truncated file behind -- and `writeText` creates the
 * destination before writing a byte, so even a write that failed
 * immediately left an empty one. Both answer `exists()`, so nothing wrote
 * them again: the shell came up without PROJECTS_DIR or with an unclosed
 * function, the editor came up with a fraction of its defaults, and the
 * only way out was clearing app data. Atomicity stops that happening from
 * now on; it does nothing for the devices it already happened to.
 *
 * WHAT THIS WILL AND WILL NOT REPAIR, because the line matters more than
 * the repair. A file is only cleared on evidence that cannot be a choice
 * someone made:
 *
 *  - empty, or nothing but NUL bytes and whitespace. Nobody means to have a
 *    zero-byte `.bashrc` or `settings.json`, and it is the shape an ENOSPC
 *    most often leaves; the NULs are what a power loss leaves on f2fs, the
 *    file's length with none of its text.
 *  - a `.bashrc` that still opens with the header this app writes but has
 *    lost the `PROJECTS_DIR` export that always followed it. Someone
 *    replacing our file writes their own; someone editing it does not
 *    usually keep our first line and delete our third. Text after the
 *    header is kept aside first. When `.bashrc` is a link, the file it
 *    names is kept elsewhere and may be read by other machines, so a header
 *    with text after it is left as it is there. The header alone is still
 *    rewritten through the link, and so is a linked file that is empty or
 *    NUL bytes (the first case): both are what a cut-short write leaves,
 *    with nothing of the person's in them.
 *
 * A partial file that is neither -- one that got far enough to look
 * plausible -- is left alone, and that is deliberate. It cannot be told
 * from a file the user shortened themselves, and clearing it would destroy
 * their work to fix a state we are only guessing at. So this narrows the
 * damage rather than ending it, and says so.
 *
 * Clearing alone would make it worse, and the first draft of this did.
 * Both writers live in `runSetupLocked`, which an already-complete install
 * never re-enters, and the every-launch repairs all open with
 * `if (bashrc.exists())`. Deleting the file would have turned a truncated
 * one into no file at all, with nothing to write it again.
 *
 * Which is why nothing here deletes. It does now CREATE, in the cases
 * where there is nothing of the user's to protect: a `.bashrc` that is gone
 * rather than short, and only once `home/` itself is there, because before
 * the first extraction has made it the write cannot do anything but fail;
 * and the file a link at settings.json, `~/.ssh/config`, `~/.bash_profile`
 * or `~/.tmux.conf` names when that file has gone from a folder that is
 * there (a plain one of those that is gone is not made again here).
 * That was the same dead end from the other direction: every writer keyed on
 * the file being there, so `rm ~/.bashrc` was permanent until the next app
 * update, and it is why this method reads `isFile` and `exists()` separately
 * rather than only the first.
 *
 * The second draft cleared the file and
 * then called the writer, whose `!exists()` guard the clear had just
 * satisfied, and that leaves the same hole one failure further along: the
 * rewrite can fail, on exactly the full disk this repair exists for, and
 * the file is then gone with every guard that could have tried again
 * keyed on its presence. [writeBashrc] and [writeDefaultSettings] are the
 * guardless halves of those two writers, so this replaces through
 * [writeAtomically]'s rename instead: on failure the bad file is still
 * there, this runs again on the next launch, and freeing space is enough
 * to heal the install.
 *
 * Runs on every launch, and before the appenders, so a file this has just
 * restored is what they extend.
 *
 * WHICH IS ALSO WHAT BOUNDS THE RETRY, and the bound arrived with the
 * delete going away. A rewrite that fails leaves the file for the next
 * launch to find, and leaves it for the rest of THIS launch too: the
 * appenders behind this one are gated on `exists()`, which a file this
 * could not rewrite still answers. What they append then decides whether
 * this recognises the file again.
 *
 *  - the header shape retries until it heals. An append lands after the
 *    header and brings no `PROJECTS_DIR`, so the test below classifies the
 *    file the same way however many launches it takes.
 *  - the empty shape retries only until an append lands on it.
 *    `createNpmWrappers` and `ensureToolchainEnvSourcing` extend a
 *    zero-byte `.bashrc` quite happily, and what they leave is neither
 *    blank nor header-first, so this stops seeing it: the install keeps the
 *    npm/claude functions and the toolchain sourcing, and loses the prompt,
 *    the exports, the aliases and the startup `cd` until Clear Data.
 *
 * That second case needs a rewrite that fails and an append moments later
 * that succeeds, and no state of the device produces the pair: every one of
 * them rewrites the whole file in the same directory, so none can
 * succeed where the rewrite failed. `createNpmWrappers` writes several times
 * the rewrite's size, so a disk or a quota that refused the rewrite refuses
 * it outright; `ensureToolchainEnvSourcing` writes a couple of hundred bytes
 * against the rewrite's couple of kilobytes, both inside one inode and one
 * block, so they fail together. A failing destination rename is
 * size-independent and stops all three alike. It takes something
 * else freeing a block in between, which is why this is written down rather
 * than closed.
 *
 * Closing it would mean testing the empty shape for what an append cannot
 * remove: a `.bashrc` with no `PROJECTS_DIR` export, whatever its first
 * bytes. That is refused, because the description fits a `.bashrc` the user
 * wrote themselves just as well, and nothing here is replaced on evidence
 * that can be their own choice. A lost prompt is worth less than their file.
 */
internal fun FirstRunSetup.repairTruncatedSetupFilesImpl() {
    val bashrc = File(context.filesDir, "home/.bashrc")
    holdingWriteLock(bashrc) {
        if (bashrc.isFile) {
            // Read as bytes and decoded only to decide: what is kept aside below is the file itself,
            // and a decoder turns every byte that is not UTF-8 into U+FFFD.
            val bytes = runCatching { bashrc.readBytes() }.getOrNull()
            val text = bytes?.let { String(it, Charsets.UTF_8) }
            val emptied = text != null && isLeftBlank(text)
            val headerWithoutBody = text != null &&
                text.startsWith(BASHRC_HEADER) &&
                !text.contains("export PROJECTS_DIR")
            // A header with nothing after it is a cut-short copy of ours. Text after the header is a
            // person's own or a longer cut, and the header proves nothing about it.
            val beyondHeader = headerWithoutBody && text.orEmpty().removePrefix(BASHRC_HEADER).isNotBlank()
            // And a link is the file they keep elsewhere, often in a repository other machines read:
            // the rewrite would put this device's paths into what the link names. Left as it is.
            val theirs = beyondHeader && Files.isSymbolicLink(bashrc.toPath())
            if ((emptied || headerWithoutBody) && !theirs) {
                // Written OVER, never deleted first. Deleting is what satisfied
                // createBashrc's `!exists()` guard, and it is also what let this
                // repair finish the job the full disk started: the rewrite can
                // fail, and the file was then absent -- outside the `isFile`
                // test this branch re-enters through, outside every per-launch
                // appender's own `exists()` guard, and outside createBashrc,
                // which runSetupLocked reaches only when the package identity
                // changes (versionName, versionCode or update time). So one launch that could not write turned a
                // repairable install into one nothing repairs until the next app
                // update. [writeBashrc] renames over the path instead: a failed
                // write leaves the bad file exactly where it was, and this
                // branch finds it again on the next launch. For the one shape
                // where an appender behind this one can take that retry away
                // before the next launch gets here, see the KDoc.
                //
                // Text after the header is kept aside before the rewrite, and the rewrite waits
                // for the next launch if it cannot be kept.
                val aside = File(context.filesDir, "home/.vscodroid/bashrc.before-repair")
                val kept = !beyondHeader || run {
                    aside.parentFile?.mkdirs()
                    // The same text again is a rewrite that failed after the text was kept. Anything
                    // else at the name is an earlier repair's, the only copy of what that one replaced,
                    // so it moves to a free name of its own before this one takes the place.
                    val same = runCatching { aside.isFile && aside.readBytes().contentEquals(bytes) }.getOrDefault(false)
                    same || (
                        (!aside.exists() || aside.renameTo(
                            generateSequence(aside.lastModified()) { it + 1 }
                                .map { File("${aside.path}.$it") }.first { !it.exists() },
                        )) && writeAtomically(aside, { Logger.w(tag, it) }) { it.write(bytes ?: ByteArray(0)) }
                        )
                }
                // The rewrite is not expected to throw (a failed write is
                // reported as false), but a throw must not skip the settings
                // repair below either.
                val rewritten = kept && runCatching { writeBashrc() }
                    .onFailure { Logger.e(tag, "The .bashrc rewrite threw", it as? Exception) }
                    .getOrDefault(false)
                if (rewritten) {
                    Logger.i(tag, "Rewrote a half-written .bashrc")
                } else {
                    Logger.e(
                        tag,
                        "Could not rewrite the half-written .bashrc. It is unchanged rather " +
                            "than gone, so there is still something for the next launch to repair.",
                    )
                }
            } else if (theirs) {
                Logger.i(tag, "Left a linked .bashrc that holds text of its own after the header as it is")
            }
        } else if (!bashrc.exists() && bashrc.parentFile?.isDirectory == true) {
            // The parent test is not defensive tidying; without it this arm fired
            // on every fresh install and could only fail. SplashActivity runs this
            // repair ahead of the isFirstRun branch, and `home/` is created by
            // createDirectories() inside runSetupLocked, which has not run yet:
            // none of the repairs before this one goes near it. So writeBashrc
            // opened `home/.bashrc.tmp~` in a directory that was not there, got
            // FileNotFoundException, returned false, and the branch below logged
            // at ERROR that the terminal has no prompt and no PATH, on 100% of
            // first launches and on every relaunch after a LOW_STORAGE refusal.
            // In a release build logcat is the only trace this project has, so a
            // line that is always wrong costs the lines that are not.
            //
            // Absent, which is the one shape nothing covered. `createBashrc` is
            // reached only from runSetupLocked, on a version change, and every
            // per-launch appender opens with `bashrc.exists()` or returns on its
            // absence -- so `rm ~/.bashrc` in the bundled terminal left the shell
            // with no prompt, no PROJECTS_DIR, no aliases, no npm/npx/claude and
            // no startup cd until the next app update.
            //
            // Recreating is not the "never replace what could be the user's own
            // edit" line this method draws elsewhere: a file that is not there
            // holds nothing of theirs, and this writes only what the app owns.
            // `isFile` above rather than `exists()` keeps a directory at the path
            // out of both branches; mkdirs would not repair that and neither
            // would this.
            //
            // A link to a file that has gone reads as absent too. It is written
            // through, never replaced (see linkTarget): the file is made where the
            // link points, or, when the folder it points into has gone as well,
            // nothing is written and the link stays, since making that folder
            // again would undo a move the person made.
            val linked = Files.isSymbolicLink(bashrc.toPath())
            val written = runCatching { writeBashrc() }
                .onFailure { Logger.e(tag, "The .bashrc rewrite threw", it as? Exception) }
                .getOrDefault(false)
            if (written) {
                Logger.i(tag, if (linked) "Wrote the file a .bashrc link names, which had gone" else "Recreated a .bashrc that had gone")
            } else if (linked) {
                Logger.w(tag, LINKED_BASHRC_UNWRITTEN)
            } else {
                Logger.e(tag, "Could not recreate the missing .bashrc; the terminal keeps no prompt, no PATH exports and no npm")
            }
        }
    }

    val settings = File(Environment.getMachineSettingsPath(context))
    holdingWriteLock(settings) {
        val linkedSettingsGone = Files.isSymbolicLink(settings.toPath()) && !settings.exists()
        if (settings.isFile && runCatching { isLeftBlank(settings.readText()) }.getOrDefault(false)) {
            // Replaced rather than deleted and rewritten, for the reason above.
            // Deleting a zero-byte file frees nothing, so the disk that emptied
            // it is still full when the rewrite runs.
            val rewritten = runCatching { writeDefaultSettings() }
                .onFailure { Logger.e(tag, "The settings.json rewrite threw", it as? Exception) }
                .getOrDefault(false)
            if (rewritten) {
                Logger.i(tag, "Rewrote an empty settings.json")
            } else {
                Logger.e(
                    tag,
                    "Could not rewrite the empty settings.json. It is still there rather than " +
                        "gone, so the next launch tries again.",
                )
            }
        } else if (linkedSettingsGone && !legacySettingsFile().exists()) {
            // A link to a settings file that has gone, met as the .bashrc arm above meets one,
            // and written through for the same reason. Setup leaves such a link as it is when
            // the folder it points into has gone too (createDefaultSettings), and every
            // per-launch writer of this file returns on its absence, so once the folder came
            // back without the file the install had no terminal profile, no git.path, no
            // claudeProcessWrapper and no verifySignature until the next update. A file at
            // the legacy path is left to migrateSettingsToMachinePath, which runs after this
            // and moves the person's own settings in through the link; defaults written here
            // first would leave them behind for good.
            val written = runCatching { writeDefaultSettings() }
                .onFailure { Logger.e(tag, "The settings.json rewrite threw", it as? Exception) }
                .getOrDefault(false)
            if (written) {
                Logger.i(tag, "Wrote the file a settings.json link names, which had gone")
            } else {
                Logger.w(tag, LINKED_SETTINGS_UNWRITTEN)
            }
        }
    }

    // The three other files setup writes through a link, met the same way and for the same
    // reason. Setup leaves such a link as it is while the folder it points into has gone, and
    // writes them only when the app updates, so a folder that came back without the file left
    // tmux windows without .bashrc and the terminal's ssh without its config (it runs bare when
    // the config cannot be read) until the next update. A plain file that is gone is the
    // person's deletion and is left to setup, as a plain settings.json is.
    for ((file, write) in listOf(
        File(context.filesDir, "home/.ssh/config") to ::writeSshConfig,
        File(context.filesDir, "home/.bash_profile") to ::writeBashProfile,
        File(context.filesDir, "home/.tmux.conf") to ::writeTmuxConf,
    )) {
        holdingWriteLock(file) {
            if (!Files.isSymbolicLink(file.toPath()) || file.exists()) return@holdingWriteLock
            val written = runCatching { write() }
                .onFailure { Logger.e(tag, "Writing the file a ${file.name} link names threw", it as? Exception) }
                .getOrDefault(false)
            if (written) {
                Logger.i(tag, "Wrote the file a ${file.name} link names, which had gone")
            } else {
                Logger.w(tag, linkedFileUnwritten(file))
            }
        }
    }
}


/**
 * Ensures .bashrc sources toolchain-env.sh for on-demand toolchain env vars.
 * Safe to call on every launch: only appends if the sourcing line is missing.
 */
internal fun FirstRunSetup.ensureToolchainEnvSourcingImpl() {
    val bashrc = File(context.filesDir, "home/.bashrc")
    holdingWriteLock(bashrc) {
        if (!bashrc.exists()) return@holdingWriteLock
        // Bytes through unchanged, decoded only to search. See
        // [createNpmWrappers] for why the round trip would be lossy.
        val existing = bashrc.readBytes()
        val content = String(existing, Charsets.ISO_8859_1)
        if (!content.contains("toolchain-env.sh")) {
            // Rewritten whole through a temporary file rather than appended
            // in place. The guard above reads the filename, which appears
            // in the comment this block opens with, so an append cut short
            // by process death satisfied the check that would have finished
            // it and left a truncated `[ -f ...` line behind for good.
            val block = """

# On-demand toolchain env vars
[ -f "${'$'}HOME/.vscodroid/toolchain-env.sh" ] && . "${'$'}HOME/.vscodroid/toolchain-env.sh"
"""
            if (writeAtomically(bashrc, throughLink = true) { it.write(existing); it.write(block.toByteArray()) }) {
                Logger.i(tag, "Appended toolchain-env.sh sourcing to .bashrc")
            } else {
                Logger.w(tag, "Could not append toolchain-env.sh sourcing; .bashrc is unchanged")
            }
        }
    }
}


/**
 * Writes the file `BASH_ENV` points at, which is what gives a NON-interactive
 * shell the commands the terminal has.
 *
 * npm, npx, pip, claude and every toolchain binary are bash functions, not
 * files, because SELinux denies execve under filesDir. They were defined in
 * `.bashrc` alone, and bash reads `.bashrc` only when it is interactive. So
 * everything that runs a command through `bash -c` -- a VS Code task, an npm
 * lifecycle script, a build step an extension spawns -- was told
 * "command not found" for a command the terminal beside it ran fine, and a
 * toolchain that had installed correctly looked broken from the editor.
 *
 * Measured against bash 3.2.57, and it is the rule rather than the version
 * that is being relied on. `bash -c 'type -t npm'`, `bash script.sh` and
 * `bash -lc` all report the function once BASH_ENV names this file; an
 * interactive shell reports nothing, because it reads `.bashrc` and never
 * this.
 *
 * The two files DO overlap, and a login shell is where. `bash -lc` is
 * non-interactive, so it reads this file, and it is also a login shell, so it
 * reads `.bash_profile`, which [createBashProfile] writes as an unguarded
 * `. "$HOME/.bashrc"`. Measured: `bash -lc` runs both, `bash -c` runs only
 * this one. So under `-lc` the wrappers are defined twice,
 * `toolchain-env.sh` is sourced twice and every installed toolchain lands on
 * PATH twice. The closing `cd` in `.bashrc` runs twice as well, and no longer
 * moves a shell out of the directory it was started in: it fires only when
 * `PWD` is `HOME`. Redefining a function and re-prepending a PATH
 * entry are both harmless, which is why the overlap is left alone rather than
 * guarded. Anything added to this file that is NOT safe to run twice has to
 * bring its own guard.
 *
 * WHAT THIS DOES NOT FIX, because the gap is narrower than "commands work
 * now" and the rest needs a different mechanism:
 *
 *  - a direct execve of the bare name. `child_process.spawn("pip", ...)` with
 *    no shell reaches no shell, so no function exists. npm, npx and the
 *    toolchain commands are past this and the next one: each has a link in
 *    `usr/libexec/tcbin`, ahead of `usr/bin` on PATH, onto the exec
 *    trampoline in `nativeLibraryDir` ([ToolchainManager.regenerateDerivedFiles]).
 *    pip and claude have no such link.
 *  - `sh -c`. Android's `sh` is mksh, which has never heard of BASH_ENV, and
 *    bash itself ignores the variable when it is invoked as `sh` or with
 *    `--posix` -- both measured. Node's `child_process.exec()` and make's
 *    default recipe shell are `/bin/sh`, so neither is covered.
 *  - anything started before this file is written. It is regenerated at every
 *    launch, ahead of the server, so that only matters on the very first one.
 *
 * Rewritten whole whenever the contents differ rather than appended to: it is
 * generated state, so there is no user edit to preserve and no guard string
 * whose presence could certify a half-written file. `.bashrc` remains the
 * interactive shell's, and keeps its appenders.
 *
 * ⚠️ This file, and `toolchain-env.sh` which it sources, must print nothing.
 * Every `$(...)` in the app now runs a shell that reads them first, so a line
 * as harmless as `echo "toolchains ready"` would land inside the output of
 * every command substitution rather than on anyone's screen. `.bashrc` is not
 * the exception it looks like: `bash -lc` reaches it through `.bash_profile`,
 * and bash's rshd/sshd branch reads it INSTEAD of this file when SSH_CLIENT
 * or SSH2_CLIENT is set, or stdin is a socket, in a top-level shell. All
 * three conditions measured on bash 3.2.57, the third included: the branch is
 * skipped as soon as an inherited SHLVL says a shell is already nested. That
 * third one is no help here, because [Environment.buildProcessEnvironment]
 * exports no SHLVL and every shell the server starts is therefore top-level.
 * What keeps the branch out of reach on device is the first two: nothing in
 * this app sets either variable (there is no sshd here, only the ssh client),
 * and the two ways a shell gets started both rule the socket out, node-pty
 * giving the terminal a real pty and a task's shell a pipe. If a third way
 * ever appears, this file is the one that stops being read. `.bashrc`'s
 * prompt block is silent only because PS1 and PROMPT_COMMAND produce nothing
 * when nobody is at a prompt; a bare `echo` beside them would carry into
 * those same substitutions.
 */
internal fun FirstRunSetup.createBashEnvFileImpl() {
    val envFile = File(Environment.getBashEnvPath(context))
    // platform-fix.js reaches a shell through NODE_OPTIONS, and VS Code deletes
    // NODE_OPTIONS from the extension host's environment. A bash the extension
    // host starts, the Claude Code panel's Bash tool among them, would then run
    // npm() with no preload to read VSCODROID_PLATFORM_FIX, so a package whose
    // install script rejects android fails there and works in a terminal. Put
    // back once here, for every non-interactive shell and the node processes
    // under it; a terminal's shell already carries it and is left alone.
    val platformFix = "${context.filesDir.absolutePath}/server/platform-fix.js"
    val preload = """

# The Node preload, when VS Code's extension host started this shell without it.
case " ${'$'}{NODE_OPTIONS-} " in
    *"/server/platform-fix.js"*) ;;
    *) export NODE_OPTIONS="--require=$platformFix${'$'}{NODE_OPTIONS:+ ${'$'}NODE_OPTIONS}" ;;
esac
"""
    val content = BASH_ENV_HEADER + preload + npmBashFunctions() + claudeBashFunction() +
        pipBashFunctions() + sshBashFunction() + scpBashFunction() + sftpBashFunction() + """

# On-demand toolchain env vars
[ -f "${'$'}HOME/.vscodroid/toolchain-env.sh" ] && . "${'$'}HOME/.vscodroid/toolchain-env.sh"
"""
    if (envFile.isFile && runCatching { envFile.readText() }.getOrNull() == content) return

    envFile.parentFile?.mkdirs()
    // Atomic because every non-interactive shell in the app sources this
    // file: a truncated copy is not a missing command but a syntax error
    // printed by every task, npm script and build the editor starts, with
    // PATH possibly half-built behind it. A failure leaves the previous
    // file, which is the last one that worked.
    if (writeAtomically(envFile) { it.write(content.toByteArray()) }) {
        Logger.i(tag, "Wrote ${envFile.name} for non-interactive shells")
    } else {
        Logger.w(tag, "Could not write ${envFile.name}; it keeps whatever it held before")
    }
}


/**
 * Brings the .bashrc prompt block up to [PROMPT_VERSION], rewriting whatever
 * older shape is there.
 *
 * The block is fenced by versioned markers so that any future change to it is
 * migratable. The first version had no markers at all: it printed straight
 * out of PROMPT_COMMAND with PS1 left empty, dating from when the terminal was
 * a pipe rather than the PTY node-pty now gives us, so that shape is also
 * recognised, by its function name and its `PS1=''`.
 *
 * Safe to call on every launch: it returns immediately once the current marker
 * is present, and a .bashrc whose prompt the user has rewritten matches no
 * anchor at all, so it is left as they wrote it.
 */
internal fun FirstRunSetup.ensurePromptFixImpl() {
    val bashrc = File(context.filesDir, "home/.bashrc")
    holdingWriteLock(bashrc) {
        if (!bashrc.exists()) return@holdingWriteLock

        // Latin-1, not UTF-8, and the choice is load-bearing twice over. It maps
        // every one of the 256 byte values to exactly one character and back, so
        // the round trip is lossless for any file the user has -- readText would
        // turn a byte that is not valid UTF-8 into U+FFFD and this method
        // rewrites the whole file, so a single Latin-1 accent in a comment or an
        // alias would be destroyed on the first launch after a PROMPT_VERSION
        // bump. And because the mapping is one byte to one character, the string
        // offsets computed below are byte offsets, which is what lets the two
        // halves be copied through as bytes while the new block goes in as UTF-8.
        // Every anchor searched for here is ASCII, so the decoding cannot change
        // what matches.
        val bytes = bashrc.readBytes()
        val content = String(bytes, Charsets.ISO_8859_1)
        if (content.contains(PROMPT_MARKER_CURRENT)) return@holdingWriteLock

        // Earliest anchor wins, so the old explanatory comment is swallowed too
        // rather than left behind describing a mechanism the file no longer uses.
        val start = listOf(PROMPT_BEGIN, LEGACY_PROMPT_COMMENT, PROMPT_ANCHOR_START)
            .map { content.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull() ?: return@holdingWriteLock

        val fenced = content.indexOf(PROMPT_END, start)
        val end = if (fenced >= 0) {
            content.indexOf('\n', fenced).takeIf { it >= 0 } ?: content.length
        } else {
            val legacy = content.indexOf(PROMPT_ANCHOR_END, start)
            if (legacy < 0) return@holdingWriteLock
            legacy + PROMPT_ANCHOR_END.length
        }

        // Through a temporary file, and that is what makes the marker safe to
        // put first. `writeText` truncates and then writes, so a rewrite killed
        // partway left a .bashrc that opened with [PROMPT_MARKER_CURRENT] --
        // the very string the early return above reads -- and ended wherever
        // the write stopped: an unclosed `__vscodroid_prompt()` body, no
        // PROJECTS_DIR, no cd into the workspace. Every later launch read the
        // marker, concluded the block was current and returned, so the terminal
        // opened on a syntax error that nothing would ever repair. Nothing
        // appears under the real name now until all of it has been written, so
        // the marker cannot certify a file that was never finished.
        // The two surviving halves go out as the bytes they came in as; only the
        // block this method owns is encoded. PROMPT_BLOCK is not pure ASCII, so
        // it cannot ride the Latin-1 mapping out with them.
        if (!writeAtomically(bashrc, throughLink = true) {
                it.write(bytes, 0, start)
                it.write(PROMPT_BLOCK.toByteArray())
                it.write(bytes, end, bytes.size - end)
            }) {
            Logger.w(tag, "Could not rewrite the .bashrc prompt block; it keeps the shape it had")
            return@holdingWriteLock
        }
        Logger.i(tag, "Rewrote the .bashrc prompt block ($PROMPT_VERSION)")
    }
}


/**
 * Guards the closing `cd` in `.bashrc` so it fires only for a shell that was
 * given no directory of its own, rewriting the unguarded shape every earlier
 * release wrote.
 *
 * [createBashrc] writes the guarded block now, but its own guard is whether
 * the file exists, and `isFirstRun` gates on the version rather than on the
 * contents, so an install that already has a `.bashrc` would keep the
 * unguarded `cd` for ever. This is the half that reaches those devices, and
 * it is why it runs at every launch rather than at setup.
 *
 * Safe to call every launch: it returns as soon as the current marker is
 * there, and a block the user edited matches [LEGACY_STARTUP_DIR_BLOCK]
 * nowhere, so it is left exactly as they wrote it.
 *
 * Latin-1 and atomic for the two reasons [ensurePromptFix] documents at
 * length: the mapping is lossless for any byte the user's file holds and
 * makes the offsets below byte offsets, so the halves either side of the
 * block go out as the bytes they came in as; and the marker is the first
 * thing in the block, so a rewrite cut short would otherwise leave a file
 * that certifies itself and is never repaired again.
 */
internal fun FirstRunSetup.ensureStartupDirGuardImpl() {
    val bashrc = File(context.filesDir, "home/.bashrc")
    holdingWriteLock(bashrc) {
        if (!bashrc.exists()) return@holdingWriteLock

        val bytes = bashrc.readBytes()
        val content = String(bytes, Charsets.ISO_8859_1)
        if (content.contains(STARTUP_DIR_MARKER_CURRENT)) return@holdingWriteLock

        // Newest first. A v1 block contains none of the v0 text, so the order does
        // not decide anything today, and it is fixed anyway so that adding a v3 is
        // one more entry rather than a question about which match wins.
        val previous = listOf(LEGACY_STARTUP_DIR_BLOCK_V1, LEGACY_STARTUP_DIR_BLOCK)
        val block = previous.firstOrNull { content.contains(it) } ?: return@holdingWriteLock
        val start = content.indexOf(block)
        val end = start + block.length

        val written = writeAtomically(bashrc, throughLink = true) {
            it.write(bytes, 0, start)
            it.write(STARTUP_DIR_BLOCK.toByteArray())
            it.write(bytes, end, bytes.size - end)
        }
        if (!written) {
            Logger.w(tag, "Could not guard the startup cd in .bashrc; it keeps the shape it had")
            return@holdingWriteLock
        }
        Logger.i(tag, "Guarded the startup cd in .bashrc ($STARTUP_DIR_VERSION)")
    }
}


/**
 * The marker [createNpmWrappers] guards the block below on.
 *
 * `npm()` was the guard until the note arrived, and it cannot be: an install
 * that already has the pair matches it, so the block would never reach the
 * devices the note is for. Keyed on the newest thing in the block instead,
 * which leaves a `.bashrc` written by an older release carrying two `npm`
 * definitions. bash takes the last one, so that is the one that runs, and
 * appending is the only way to change a file the user is free to edit.
 */
private val npmBlockMarker = "__vscodroid_symlink_note()"


private fun npmBashFunctions(): String = """

# npm/npx: shell functions (SELinux blocks exec of scripts under filesDir)
# VSCODROID_PLATFORM_FIX=1: override process.platform to "linux" for npm only
# (child processes like Rollup/esbuild see real "android" platform)
# --prefer-offline: use local cache first, saves time on slow mobile connections
npm() {
    VSCODROID_PLATFORM_FIX=1 node "${'$'}PREFIX/lib/node_modules/npm/bin/npm-cli.js" --prefer-offline "${'$'}@"
    # Captured before anything else runs, and handed back below, so wrapping the
    # command cannot change what a script or a task sees.
    local __npm_status=${'$'}?
    [ ${'$'}__npm_status -eq 0 ] || __vscodroid_symlink_note
    return ${'$'}__npm_status
}
npx() { VSCODROID_PLATFORM_FIX=1 node "${'$'}PREFIX/lib/node_modules/npm/bin/npx-cli.js" "${'$'}@"; }

# One line of cause when npm fails somewhere that cannot hold a symbolic link.
# npm writes node_modules/.bin/<name> as a link for every package that ships an
# executable, which is most of them, and shared storage is served through FUSE,
# which has no symlink(2) at all: the install dies with EPERM on a .bin path that
# says nothing about where the folder lives. Measured on an API 37 emulator:
# `ln -s` under Android/data answers "Permission denied" where the same call
# under the app's own files directory succeeds.
#
# The directory is ASKED rather than its path matched, so this says nothing about
# a workspace it does not recognise and nothing at all where links do work. Once
# per shell, because it is a property of the folder rather than of the command,
# and on stderr, because BASH_ENV is read by the shell behind every $(...) and
# stdout there belongs to whoever wrote the substitution.
__vscodroid_symlink_note() {
    [ -n "${'$'}{__VSCODROID_SYMLINK_NOTED-}" ] && return 0
    # A directory nobody may write refuses the probe for its own reason, and a
    # note blaming symlinks for that would be wrong.
    [ -w . ] || return 0
    local probe=".vscodroid-symlink-probe.${'$'}${'$'}"
    if ln -s . "${'$'}probe" 2>/dev/null; then
        rm -f "${'$'}probe" 2>/dev/null
        return 0
    fi
    __VSCODROID_SYMLINK_NOTED=1
    echo "vscodroid: this folder is on shared storage, which cannot hold symbolic links," >&2
    echo "vscodroid: so npm cannot create node_modules/.bin here. Move the project to" >&2
    echo "vscodroid: internal storage and open it there:  mv \"${'$'}PWD\" ~/" >&2
}
"""


/**
 * `pip` and `pip3` in the terminal, which the Get Started walkthrough promises.
 *
 * pip is installed and works; it simply has no name on PATH. It ships as a
 * package inside site-packages rather than as a program, and the console
 * script that would carry the name is a text file with a `#!` line, which
 * SELinux will not execute from filesDir. So the walkthrough said "Python +
 * pip: ready" and the first thing a person typed answered
 * `bash: pip: command not found`, on an interpreter that would have run the
 * same command perfectly as `python3 -m pip`.
 *
 * A function for the same reason npm is one, and with the same ceiling: it
 * reaches a bash shell and not a `"type": "process"` task, which is why the
 * walkthrough and the guide name the module form beside it.
 *
 * `python3` and not `python`, because [setupToolSymlinks] guarantees the
 * first and the second is only conventionally the same thing.
 */
private fun pipBashFunctions(): String = """

$PIP_BLOCK_HEADER pip is installed as a library, not as a program:
# its console script is a #! text file under filesDir, which SELinux refuses to
# execute. The module form is the same pip and always has been.
#
# Started through a few lines of Python rather than `python3 -m pip`, so that
# what pip reported can be read once it exits. A handler on the "pip" logger
# copies every ERROR record, with the context a pip diagnostic carries, to a
# file for this one run; the note below reads it and the terminal shows exactly
# what it would have. The context matters: a build dependency that failed and
# one that was never found share the same top-level line.
# A write the file cannot take once it is open, as on a full disk, is dropped:
# the file only feeds the note, and logging's default handleError prints a
# traceback per record ahead of pip's own message. Overridden on this handler,
# not through logging.raiseExceptions, which would silence pip's handlers too.
# pip's own --log, or PIP_LOG, is the obvious channel and is not used. It lowers
# pip's root logger to DEBUG, pip then counts a build's output as already shown,
# and the error box that held the build's traceback reads "No available output.".
# -P because -c puts the working directory first on sys.path: a logging.py or a
# runpy.py in the user's folder would replace the module imported here before
# pip's __main__ drops that entry, and pip would not start at all.
# _run_module_as_main is the function -m itself calls, so a venv made without
# pip gets the one line -m prints; run_module raised the same error as a
# traceback through these lines.
pip() {
    local __pip_errors="${'$'}{TMPDIR:-/tmp}/vscodroid-pip.${'$'}{BASHPID:-${'$'}${'$'}}.log"
    # Recorded through ||, which errexit ignores, so under set -e a failed install
    # still removes its file and still returns pip's own status.
    local __pip_status=0
    python3 -P -c 'import logging, runpy, sys
class Errors(logging.StreamHandler):
    def format(self, record):
        return record.getMessage() + "".join(str(getattr(a, "context", "")) for a in record.args or ())
    def handleError(self, record):
        pass
try:
    errors = Errors(open(sys.argv.pop(1), "w"))
    errors.setLevel(logging.ERROR)
    logging.getLogger("pip").addHandler(errors)
except OSError:
    pass
runpy._run_module_as_main("pip")' "${'$'}__pip_errors" "${'$'}@" || __pip_status=${'$'}?
    __vscodroid_pip_explain "${'$'}__pip_status" "${'$'}__pip_errors" "${'$'}@"
    rm -f "${'$'}__pip_errors"
    return ${'$'}__pip_status
}
pip3() { pip "${'$'}@"; }

# A short note when pip reports a failed build. The optional C/C++ toolchain
# supports native builds, but packages may still need Android-compatible build
# tools, dependencies or a prebuilt wheel. Pip's own error remains the diagnosis.
#
# The exit status cannot say which failure it was: pip returns 1 for a typo, a
# network drop and a failed build alike. The records pip() kept can, so the note
# speaks only when they name a build: a wheel, a metadata step (spaced at the top
# level, hyphenated in a build dependency's output) or the requirements hook.
# Not "Failed to build" alone, which pip also says for a failed git clone and
# for a build dependency that was never found. Still hedged, because a build
# can fail for a reason other than C. Once per shell, and spent only when it
# speaks, because it is a property of the device rather than of the command.
#
# tkinter and turtle are keyed on the name instead, and a success hears it too:
# `tkinter` is not on PyPI, and `tk` is an unrelated package that installs.
#
# Narrowed to `install`, so a mistyped `pip list` says nothing. On stderr,
# because BASH_ENV is read by the shell behind every ${'$'}(...) and stdout there
# belongs to the substitution.
#
# "Once per shell" has the ceiling the npm note has: a pipeline runs pip in a
# subshell, so the flag is set where it cannot be seen again and a session that
# only ever pipes pip can be told twice. Measured: two plain `pip install` runs
# print it once, two piped runs print it once each. Not worth a state file.
__vscodroid_pip_explain() {
    local __status=${'$'}1 __errors=${'$'}2 __arg __command= __tk= __value=
    shift 2
    # The subcommand is the first word that is not an option: `pip -q install x`.
    # Nor is the word after an option that takes its value separately, before
    # the subcommand or after it, so neither `pip --cache-dir c install x` nor
    # `pip install -t tk x` is misread. The list is pip 26.2.1's value options,
    # general and install. An abbreviated (`--tar`) or clustered (`-Ut`)
    # spelling is not recognised; at worst a note is lost or shown when it
    # should not be, and pip's own result never changes.
    for __arg in "${'$'}@"; do
        if [ -n "${'$'}__value" ]; then __value=; continue; fi
        case "${'$'}__arg" in
            -[rcetCif]|--python|--log|--log-file|--local-log|--keyring-provider|--proxy|\
            --retries|--timeout|--default-timeout|--exists-action|--trusted-host|--cert|\
            --client-cert|--cache-dir|--use-feature|--use-deprecated|--resume-retries|\
            --requirement|--constraint|--build-constraint|--requirements-from-script|\
            --editable|--target|--platform|--python-version|--implementation|--abi|\
            --root|--prefix|--src|--source|--source-dir|--source-directory|\
            --upgrade-strategy|--config-settings|--progress-bar|--root-user-action|\
            --report|--group|--all-releases|--only-final|--no-binary|--only-binary|\
            --index-url|--pypi-url|--extra-index-url|--refresh-package|--find-links|\
            --uploaded-prior-to) __value=1 ;;
            -*) ;;
            *)  if [ -z "${'$'}__command" ]; then __command=${'$'}__arg
                else case "${'$'}__arg" in [Tt]kinter|[Tt]k|turtle) __tk=1 ;; esac
                fi ;;
        esac
    done
    [ "${'$'}__command" = install ] || return 0
    if [ -n "${'$'}__tk" ]; then
        echo "vscodroid: tkinter and turtle are not included, and pip cannot add them;" >&2
        echo "vscodroid: 'tk' on PyPI is an unrelated package." >&2
        echo "vscodroid: https://rmyndharis.github.io/VSCodroid/guide.html#python-packages-written-in-c" >&2
    fi
    [ "${'$'}__status" -eq 0 ] && return 0
    [ -n "${'$'}{__VSCODROID_PIP_NOTED-}" ] && return 0
    grep -qE 'Failed building |metadata.generation.failed| when getting requirements to build' "${'$'}__errors" 2>/dev/null || return 0
    __VSCODROID_PIP_NOTED=1
    echo "vscodroid: a package failed to build. Native builds may need the optional C/C++ toolchain" >&2
    echo "vscodroid: and Android-compatible dependencies, or a compatible prebuilt wheel." >&2
    echo "vscodroid: Check pip's error output for the cause." >&2
    echo "vscodroid: https://rmyndharis.github.io/VSCodroid/guide.html#python-packages-written-in-c" >&2
}
"""


/**
 * Points pip at the wheels this project mirrors, and at nothing else.
 *
 * The device's interpreter accepts `cp314-cp314-android_24_arm64_v8a` and
 * PyPI publishes almost nothing for it, so `pip install pandas` can fall back
 * to an sdist that requires additional native tools and dependencies. The
 * optional C/C++ toolchain does not supply every package's build environment.
 * The wheels exist, prebuilt, and
 * `wheelhouse.json` names the ones we mirror; this is the line that makes
 * pip look at them.
 *
 * `prefer-binary` earns its place even when the mirror is empty: it makes
 * the resolver stop at the newest version that HAS a usable wheel rather
 * than chasing the newest version into an sdist and the same wall. It is
 * deliberately not `--only-binary=:all:`, which is the tempting blunt
 * version and is wrong: a pure-Python sdist builds fine here, because that
 * build runs python and python lives in nativeLibraryDir where execve is
 * allowed. The cost to accept: for a package whose newest release is
 * sdist-only, a user silently gets an older version, and `pip install
 * pkg==<version>` is the answer.
 *
 * Written to pip's legacy per-user file, `~/.pip/pip.conf`, and not to
 * `~/.config/pip/pip.conf`. pip reads both and lets the second override the
 * first key by key, and `pip config set` writes the second, so anything a
 * user configures wins over this without the app ever editing their file.
 * Merging keys into that file instead would have to survive a user's own
 * `[global]` header, which pip refuses to see twice, and would replace a
 * `find-links` they set on every launch. The legacy path is also
 * `expanduser("~")` rather than a platformdirs lookup, so it is the same
 * file whatever environment pip runs in.
 *
 * Owned only while it carries [PIP_CONF_HEADER] or is blank: a
 * `~/.pip/pip.conf` a user wrote themselves, or one that cannot be read, is
 * left alone, and pip then simply reads theirs. Rewritten on every launch
 * otherwise, so a changed URL reaches a device already in someone's hands
 * without re-extraction. An APK with no Python writes nothing.
 */
internal fun FirstRunSetup.ensurePipConfigImpl() {
    val runtime = pythonRuntimeInAssets() ?: return
    val minor = runtime.removePrefix("libpython").removeSuffix(".so")
    val conf = File(context.filesDir, "home/.pip/pip.conf")
    val content = pipConfigContent(minor)
    holdingWriteLock(conf) {
        val existing = if (conf.exists()) {
            // A file that cannot be read may be the user's, and is left alone.
            runCatching { conf.readText() }.getOrElse {
                Logger.w(tag, "Could not read ${conf.path}; leaving it alone")
                return@holdingWriteLock
            }
        } else {
            null
        }
        if (existing == content) return@holdingWriteLock
        // Blank is ours, as it is for .bashrc and settings.json.
        val blank = existing != null && isLeftBlank(existing)
        if (existing != null && !blank && !existing.startsWith(PIP_CONF_HEADER)) {
            Logger.i(tag, "Left ${conf.path} alone: it was not written by this app")
            return@holdingWriteLock
        }
        conf.parentFile?.mkdirs()
        // Through a link, as every file here a person may keep elsewhere is written.
        if (writeAtomically(conf, throughLink = true) { it.write(content.toByteArray()) }) {
            Logger.i(tag, "Pointed pip at the wheelhouse")
        } else {
            Logger.w(tag, "Could not write pip.conf; pip keeps whatever it had")
        }
    }
}


/**
 * The marker [createNpmWrappers] guards the pip block on.
 *
 * `pip()` was the guard and could not stay one, for the reason
 * [npmBlockMarker] records: every install that already has the pair matches
 * it, so the block froze at whatever the release which first wrote the file
 * happened to say, and no later launch could ever change it. Keyed on the
 * newest thing in the block instead, which leaves a `.bashrc` written by an
 * older release carrying two `pip` definitions. bash takes the last one, so
 * the new one runs.
 *
 * This is the only channel that reaches a device already in someone's hands:
 * [createNpmWrappers] runs from the unconditional per-launch repair block
 * rather than behind [isFirstRun], so the file is reconciled on the next
 * launch after an app update, with no re-extraction and nothing moving in
 * the storage pre-flight.
 */
private val pipBlockMarker = "Native builds may need the optional C/C++ toolchain"


/** The comment every pip block this app has written opens with, v1.3.0's included. */
private val PIP_BLOCK_HEADER = "# pip/pip3: shell functions."


/**
 * A `pip` definition at the start of a line, whoever wrote it, in every spelling
 * bash accepts: `pip()`, `pip ()`, `function pip {` and `function pip()`. Only
 * `pip()` was matched, so a v1.3.0 user's `function pip { ... }` below the old
 * block counted as nothing and the new block was appended after it, replacing it.
 */
private val PIP_DEFINITION = Regex("""(?m)^\s*(?:function\s+pip(?=[\s({]|$)|pip\s*\(\s*\))""")


/** The same for `pip3`, which every pip block this app writes also defines. */
private val PIP3_DEFINITION = Regex("""(?m)^\s*(?:function\s+pip3(?=[\s({]|$)|pip3\s*\(\s*\))""")


private val CLAUDE_DEFINITION = Regex("""(?m)^\s*(?:function\s+claude(?=[\s({]|$)|claude\s*\(\s*\))""")


/** Comments each OpenSSH client wrapper block opens with. */
private val SSH_BLOCK_HEADER = "# ssh: shell function."
private val SCP_BLOCK_HEADER = "# scp: shell function."
private val SFTP_BLOCK_HEADER = "# sftp: shell function."


private fun sshClientDefinitionPattern(command: String): Regex =
    Regex("""(?m)^\s*(?:function\s+$command(?=[\s({]|$)|$command\s*\(\s*\))""")


private val SSH_DEFINITION = sshClientDefinitionPattern("ssh")
private val SCP_DEFINITION = sshClientDefinitionPattern("scp")
private val SFTP_DEFINITION = sshClientDefinitionPattern("sftp")


private data class OpenSshWrapperSpec(
    val command: String,
    val header: String,
    val definition: Regex,
    val functionText: String,
)


private fun openSshBashFunctions(): List<OpenSshWrapperSpec> = listOf(
    OpenSshWrapperSpec("ssh", SSH_BLOCK_HEADER, SSH_DEFINITION, sshBashFunction()),
    OpenSshWrapperSpec("scp", SCP_BLOCK_HEADER, SCP_DEFINITION, scpBashFunction()),
    OpenSshWrapperSpec("sftp", SFTP_BLOCK_HEADER, SFTP_DEFINITION, sftpBashFunction()),
)


private fun sshClientBashFunction(command: String, header: String): String = """

$header The bundled OpenSSH finds ~ at the prefix Termux compiled in, not at this
# app's home, so run bare it never reads ~/.ssh/config, looks for the key in a path
# that is not there and cannot save known_hosts. The app's config names every path
# absolutely, so read it explicitly. A -F typed after this one wins; with no config
# the bare command runs, because -F on a missing file is an error. Using the
# `function` keyword avoids interactive bash alias expansion turning a user's
# `alias $command=...` into a syntax error in .bashrc.
function $command {
    if [ -r "${'$'}HOME/.ssh/config" ]; then
        command $command -F "${'$'}HOME/.ssh/config" "${'$'}@"
    else
        command $command "${'$'}@"
    fi
}
"""


/**
 * `ssh`, `scp` and `sftp` in a shell, reading the config [setupSshDefaults] writes.
 *
 * The bundled OpenSSH is Termux's, and it finds the user's files through the
 * home directory its build compiled in, `/data/data/com.termux/files/home`, not
 * through `$HOME`. Run bare they therefore never read `~/.ssh/config`, so a host
 * alias in it is "Could not resolve hostname"; they look for `~/.ssh/id_ed25519`
 * where no key is, so key authentication is never tried; and they cannot save
 * `known_hosts`, so every connection is the first one and the warning prints
 * every time. That config names every path absolutely, and git already reads it
 * through `GIT_SSH_COMMAND -F` whenever it can be read; this gives the shell the
 * same, which is what the user guide describes `ssh`, `scp` and `sftp` doing.
 *
 * Functions, for the reason npm is one: nothing under `filesDir` can be
 * executed as a wrapper script, and `scp`/`sftp` otherwise invoke `usr/bin/ssh`
 * directly via `execvp`, bypassing the `ssh()` bash function. Without a readable
 * config the bare command runs, because `-F` naming a missing file is a fatal
 * error and a user who deleted the file should still get working commands.
 * A `-F` typed after ours wins, since `ssh`, `scp` and `sftp` keep the last `-F`
 * given to `ssh`.
 */
private fun sshBashFunction(): String = sshClientBashFunction("ssh", SSH_BLOCK_HEADER)

private fun scpBashFunction(): String = sshClientBashFunction("scp", SCP_BLOCK_HEADER)

private fun sftpBashFunction(): String = sshClientBashFunction("sftp", SFTP_BLOCK_HEADER)


/** Only the complete block shipped by earlier releases is replaced. */
private val legacyClaudeBlock = """

# claude: the CLI the Claude Code extension brings with it. Started through
# musl's loader: the CLI is a musl binary under filesDir, which SELinux will not
# execve but will let a loader map. libldmusl.so is found on PATH, which already
# includes nativeLibraryDir.
claude() {
    local cli="" c
    # An update can leave two versioned directories side by side, which made the
    # old echo-glob expand to two space-joined paths and fail the -f test. Take
    # the most recently installed candidate; -nt is a bash builtin, so this
    # leans on nothing external. [Aa] covers a gallery serving the publisher
    # name lowercased.
    for c in "${'$'}HOME"/.vscodroid/extensions/[Aa]nthropic.claude-code-*/resources/native-binary/claude; do
        [ -f "${'$'}c" ] || continue
        if [ -z "${'$'}cli" ] || [ "${'$'}c" -nt "${'$'}cli" ]; then cli="${'$'}c"; fi
    done
    if [ ! -f "${'$'}cli" ]; then
        echo "claude: install the Claude Code extension first" >&2
        return 127
    fi
    libldmusl.so "${'$'}cli" "${'$'}@"
}
"""


/**
 * The same block as release 1.1.0 wrote it, which spelled the first line with a dash. The file is
 * searched as Latin-1 text, where the three UTF-8 bytes of that dash read as the three characters
 * below, so the block above never matched a file from that release.
 */
private val legacyClaudeBlockFrom110 =
    legacyClaudeBlock.replace("# claude: the CLI", "# claude \u00E2\u0080\u0094 the CLI")


/**
 * `claude` in the terminal, which the extension's own login screen suggests.
 *
 * A function for the same reason npm is one: SELinux denies exec of anything
 * under filesDir, and the CLI lives there: it ships inside the extension the
 * user installed. It runs that very file rather than a second copy, so the
 * terminal and the extension are always on the same version, and the glob
 * picks up whatever version is installed without this needing to change.
 */
private fun claudeBashFunction(): String = """

# claude: the CLI the Claude Code extension brings with it. Started through
# libclaude-launch.so, which execs musl's loader with the seccomp shim as its
# --preload option: the CLI is a musl binary under filesDir, which SELinux will not
# execve but will let a loader map, and its runtime calls a syscall Android does
# not allow before android15 (see the message below). Both are found on PATH,
# which already includes nativeLibraryDir.
claude() {
    local cli="" c
    # An update can leave two versioned directories side by side, which made the
    # old echo-glob expand to two space-joined paths and fail the -f test. Take
    # the most recently installed candidate; -nt is a bash builtin, so this
    # leans on nothing external. [Aa] covers a gallery serving the publisher
    # name lowercased.
    for c in "${'$'}HOME"/.vscodroid/extensions/[Aa]nthropic.claude-code-*/resources/native-binary/claude; do
        [ -f "${'$'}c" ] || continue
        if [ -z "${'$'}cli" ] || [ "${'$'}c" -nt "${'$'}cli" ]; then cli="${'$'}c"; fi
    done
    if [ ! -f "${'$'}cli" ]; then
        echo "claude: install the Claude Code extension first" >&2
        return 127
    fi
    libclaude-launch.so "${'$'}cli" "${'$'}@"
    # 159 is 128+31, the shell's way of saying the process was killed by SIGSYS,
    # which is what an app gets for a syscall bionic does not expose. The CLI's
    # runtime calls epoll_pwait2, absent from SYSCALLS.TXT until android15, and
    # libseccomp-shim.so answers exactly that call so the CLI runs on the older
    # releases too. Its handler answers every other refused call with -ENOSYS
    # rather than letting the signal through, so reaching here means the shim
    # never loaded, and a bare "Bad system call" would not say so.
    local status=${'$'}?
    if [ "${'$'}status" -eq 159 ]; then
        echo "claude: the CLI was killed for a system call this Android does not" >&2
        echo "        allow, which libseccomp-shim.so is supposed to answer. It" >&2
        echo "        answers every refused call once it is loaded, so check that" >&2
        echo "        it sits beside libclaude-launch.so in the app's native" >&2
        echo "        library directory." >&2
    fi
    return ${'$'}status
}
"""


internal fun FirstRunSetup.createWelcomeProjectImpl() {
    val projectsDir = File(Environment.getProjectsDir(context))
    val welcomeFile = File(projectsDir, "README.md")
    if (!welcomeFile.exists()) {
        val content = """
            # Welcome to VSCodroid

            This is your default projects directory. Create folders here to start coding.

            Your default projects are stored at:
            `${projectsDir.absolutePath}/`

            The same directory is `~/projects` in the terminal, which is where
            new terminals start.

            **Command Palette**: Ctrl+Shift+P opens it. Tap Ctrl on the key row
            above the keyboard, then Shift, then press P.

            **Terminal**: Node.js, Python, Git and Bash are bundled and ready to
            use. Run `node -v` or `python3 -V` to check.
        """.trimIndent() + "\n"
        // Same shape as the ssh config beside it, and for the same reason,
        // though the consequence here is only cosmetic: writeText truncates
        // first, so a kill mid-write left a half sentence under a name whose
        // own existence is the guard, greeting every later launch. A warning
        // rather than a throw, because a missing greeting is not worth
        // failing an install over.
        if (!writeAtomically(welcomeFile) { it.write(content.toByteArray()) }) {
            Logger.w(tag, "Could not write the welcome README; the projects directory keeps what it had")
        }
    }
}


/**
 * Keeps `~/projects` pointing at the workspace [Environment] computes.
 *
 * Two things were wrong with creating it once. The guard was `!link.exists()`,
 * and `exists()` FOLLOWS the link, so a dangling one -- the ordinary state
 * after the projects directory is deleted from outside the app, which is why
 * [ensureProjectsDir] exists at all -- read as absent, and `Os.symlink` then
 * failed with EEXIST into a debug log. Every other symlink writer in this
 * file asks `Os.lstat` for exactly that reason. And it ran only from
 * `runSetupLocked`, which a complete install re-enters only when the version
 * moves, so a link that went stale stayed stale until the next app update
 * while `ensureProjectsDir()` was repairing the directory it points at on
 * every launch.
 *
 * Public and idempotent so it can sit beside that repair in the per-launch
 * block: three stats when the link is already right.
 */
internal fun FirstRunSetup.createStorageSymlinksImpl() {
    val homeDir = File(context.filesDir, "home")
    val projectsDir = Environment.getProjectsDir(context)

    // ~/projects -> the projects dir, wherever this install keeps it (convenience symlink)
    val link = File(homeDir, "projects")
    // Asked before anything is removed, and that order is the point. It used
    // to sit below the delete, so a link whose target had moved was unlinked
    // and then, with the new target not yet on disk, the method returned:
    // `~/projects` was gone where a wrong but usable link had been, and the
    // terminal that starts there had nothing to start in. Nothing is taken
    // away until there is something to put back. ensureProjectsDir() runs
    // immediately before this in both call paths, so the window is narrow,
    // but this method is public and per-launch and the ordering was all that
    // held it shut.
    if (!File(projectsDir).isDirectory) return
    val present = try { Os.lstat(link.absolutePath); true } catch (e: Exception) { false }
    if (present) {
        val current = runCatching { Os.readlink(link.absolutePath) }.getOrNull()
        // Not a link at all is the user's own directory under our name, and
        // replacing it would take their files with it.
        if (current == null || current == projectsDir) return
        // Through [unlinkStale], as every link replacement in this file now
        // is: the answer used to be discarded here and at its four siblings,
        // so a refusal fell through to Os.symlink, came back EEXIST, and left
        // the link pointing at a directory that had moved with nothing but a
        // Logger.d to say so. Terminals start here.
        if (!unlinkStale(link, "projects symlink")) return
    }
    try {
        Os.symlink(projectsDir, link.absolutePath)
    } catch (e: Exception) {
        Logger.d(tag, "Failed to create projects symlink: ${e.message}")
    }
}


internal fun FirstRunSetup.createBashrcImpl() {
    val bashrc = File(context.filesDir, "home/.bashrc")
    holdingWriteLock(bashrc) {
        if (!bashrc.exists()) {
            // Thrown, not logged. This runs only from runSetupLocked, whose
            // markSetupComplete() is the last statement of the same try block --
            // so swallowing the failure certifies an install that has no
            // .bashrc, and isFirstRun() is keyed on versionName, versionCode and package update time,
            // so setup does not run again until the app updates. Of the every-launch repairs only
            // repairTruncatedSetupFiles would make the file, a launch later; createNpmWrappers,
            // ensureToolchainEnvSourcing and ensurePromptFix all open with `if (bashrc.exists())`.
            //
            // The throw and the atomic write are worth exactly nothing apart.
            // The throw alone is what main did, and main's retry then skipped
            // the file, because writeText had already created a truncated one
            // that satisfied the guard above. Atomicity alone leaves the file
            // absent but tells no one, so no retry is offered. Together the
            // retry starts from a clean slate and produces a working shell.
            if (!writeBashrc()) {
                // Not for a link: it is written through, and one into a folder that has gone is the
                // person's and stays (see linkTarget). Throwing then would stop setup on every update
                // for as long as the folder is away; the launch repair writes it once it can.
                if (!Files.isSymbolicLink(bashrc.toPath())) throw IOException("could not write $bashrc")
                Logger.w(tag, LINKED_BASHRC_UNWRITTEN)
            }
        }
    }
}


/**
 * Writes the `.bashrc` this app owns, over whatever is at the path.
 *
 * Split from [createBashrc]'s `!exists()` guard so that
 * [repairTruncatedSetupFiles] can replace a file it has judged unusable
 * without first deleting it to satisfy that guard. Deleting is what made
 * the repair able to destroy the install it exists to rescue: the writer
 * can fail, and the file was then absent, which is outside the repair's own
 * re-entry test and outside every per-launch appender, so nothing tried
 * again until the next app update. Going through the rename leaves the bad
 * file in place on failure, so the repair fires again on the next launch
 * and heals as soon as the disk has room.
 *
 * @return true if `.bashrc` now holds what this writes. On false it is
 *   untouched, which is [writeAtomically]'s contract and the whole of what
 *   makes a retry worth offering.
 */
internal fun FirstRunSetup.writeBashrc(): Boolean {
    val bashrc = File(context.filesDir, "home/.bashrc")
    // Atomic for the reason the rewrite in ensurePromptFix() is: the
    // caller's guard is the file's own existence, so a first-run write
    // that stopped partway left a .bashrc that answered `exists()` and
    // was never written again -- the prompt half-defined and the
    // PROJECTS_DIR export missing, on a device that had never had a
    // working shell to compare against.
    val text = bashrcText().toByteArray()
    return writeAtomically(bashrc, throughLink = true) { it.write(text) }
}


/** What [writeBashrc] writes. */
internal fun FirstRunSetup.bashrcText(): String {
    val projectsDir = Environment.getProjectsDir(context)
    val safMirrorsDir = Environment.getSafMirrorsDir(context)
    return BASHRC_HEADER + "\n" + PROMPT_BLOCK + "\n\n" + """
        export PROJECTS_DIR='$projectsDir'
        export SAF_MIRRORS_DIR='$safMirrorsDir'
        alias ls='ls --color=auto'
        alias ll='ls -la'

        # On-demand toolchain env vars
        [ -f "${'$'}HOME/.vscodroid/toolchain-env.sh" ] && . "${'$'}HOME/.vscodroid/toolchain-env.sh"
    """.trimIndent() + "\n\n" + STARTUP_DIR_BLOCK + "\n"
}


internal fun FirstRunSetup.createBashProfileImpl() {
    val bashProfile = File(context.filesDir, "home/.bash_profile")
    holdingWriteLock(bashProfile) {
        if (!bashProfile.exists()) {
            // Atomic and loud, the same pair createBashrc is, and for the same
            // pair of reasons. `writeText` creates and truncates before writing
            // a byte, so a write that failed left a file the guard above
            // accepts for ever, and this writer is reached only from
            // runSetupLocked, which isFirstRun() gates on versionName,
            // versionCode and package update time: the per-launch repair writes
            // this path only through a link whose file has gone. What
            // that costs is confined to INTERACTIVE LOGIN shells, which is what
            // a tmux window is. Those read .bash_profile and never .bashrc or
            // BASH_ENV, so they come up with no prompt, no aliases and no
            // npm/npx/claude while the editor's own terminals (non-login) and
            // `bash -c` (BASH_ENV) are unaffected. Throwing reaches
            // runSetupLocked's catch, and the Retry it offers then starts from
            // an absent file rather than a truncated one. Through a link, as
            // createBashrc writes, and not thrown for one into a folder that has
            // gone, for the reason it gives.
            if (!writeBashProfile()) {
                if (!Files.isSymbolicLink(bashProfile.toPath())) throw IOException("could not write $bashProfile")
                Logger.w(tag, linkedFileUnwritten(bashProfile))
            }
        }
    }
}


/** The guardless half of [createBashProfile], through a link at the path. */
internal fun FirstRunSetup.writeBashProfile(): Boolean {
    val content = """
        # Source .bashrc for login shells (e.g. tmux sessions)
        if [ -f "${'$'}HOME/.bashrc" ]; then
            . "${'$'}HOME/.bashrc"
        fi
    """.trimIndent() + "\n"
    return writeAtomically(File(context.filesDir, "home/.bash_profile"), throughLink = true) {
        it.write(content.toByteArray())
    }
}


internal fun FirstRunSetup.createTmuxConfImpl() {
    val tmuxConf = File(context.filesDir, "home/.tmux.conf")
    holdingWriteLock(tmuxConf) {
        if (!tmuxConf.exists()) {
            // Atomic like its neighbours, so a failed write leaves nothing
            // rather than a truncated file that answers the guard above for
            // ever. Logged rather than thrown, which is where it parts from
            // .bash_profile beside it: what is lost is the mouse, the colours
            // and the scrollback, and tmux starts perfectly well without them,
            // so failing the whole unpack over five options would cost the user
            // more than the options are worth. Through a link, as createBashrc
            // writes, so one to a file kept elsewhere stays a link.
            if (!writeTmuxConf()) {
                Logger.w(tag, "Could not write $tmuxConf; tmux will run with its own defaults")
            }
        }
    }
}


/** The guardless half of [createTmuxConf], through a link at the path. */
internal fun FirstRunSetup.writeTmuxConf(): Boolean {
    val content = """
        # VSCodroid tmux configuration
        set -g mouse on
        set -g default-terminal "xterm-256color"
        set -g history-limit 10000
        set -g escape-time 10
        set -g status off
    """.trimIndent() + "\n"
    return writeAtomically(File(context.filesDir, "home/.tmux.conf"), throughLink = true) {
        it.write(content.toByteArray())
    }
}


private const val LINKED_BASHRC_UNWRITTEN =
    "Could not write the file a .bashrc link names, and left the link as it is; " +
        "the terminal starts without this app's setup until that file can be written"


/** Said when setup cannot write the file a link at [link] names; the link is left as it is (see linkTarget). */
internal fun linkedFileUnwritten(link: File) =
    "Could not write the file the ${link.name} link names, and left the link as it is"


/** Said when the file a settings.json link names cannot be written, which leaves the editor without this app's settings. */
internal const val LINKED_SETTINGS_UNWRITTEN =
    "Could not write the file a settings.json link names, and left the link as it is; " +
        "the editor runs without this app's machine settings until that file can be written"


/**
 * The first line of every `.bashrc` this app writes.
 *
 * One constant so the writer and [FirstRunSetup.repairTruncatedSetupFiles]
 * cannot drift: the repair decides a file is half-written partly by finding
 * this line with nothing that should follow it, and if the writer's wording
 * moved the repair would silently stop recognising its own output.
 */
private const val BASHRC_HEADER = "# VSCodroid bash configuration"


/**
 * A line of `.npmrc` in a shape only this app writes: a script shell that is the bundled bash, and
 * the platform lines of this and earlier versions.
 */
private val DEVICE_NPMRC_LINE =
    Regex("""^\s*(script-shell\s*=\s*\S*/libbash\.so|os\s*=\s*android|os\[]\s*=\s*(linux|android))\s*$""")


/**
 * What opens the file `BASH_ENV` names.
 *
 * Says out loud that the file is generated, because it is rewritten whenever its
 * contents change and an edit made here would be gone by the next launch. The
 * `.bashrc` beside it is the opposite: appended to, never regenerated, so a user
 * may edit that one freely.
 */
private const val BASH_ENV_HEADER = """# VSCodroid: sourced by NON-INTERACTIVE bash through BASH_ENV.
# Generated at every launch -- edit ~/.bashrc instead, which is yours.
#
# npm, npx, pip, claude and the toolchain binaries are shell functions because
# SELinux will not execute a file under this app's data directory. Functions
# live in .bashrc, which only an INTERACTIVE bash reads, so without this file a
# task or an npm lifecycle script gets "command not found" for a command the
# terminal runs fine. An interactive shell never reads this file, but a login
# shell such as `bash -lc` reads both, because .bash_profile sources .bashrc, so
# everything here has to be safe to run twice.
#
# Nothing in here, or in the toolchain-env.sh it sources, may print: this file
# is read by the shell behind every $(...), so a stray echo ends up inside that
# command's output instead of on a screen.
"""


/** Bumped whenever [PROMPT_BLOCK] changes, so an older block is recognised and replaced. */
/**
 * Where an interactive shell starts when, and only when, nobody chose for it.
 *
 * bash runs `.bashrc` for every interactive shell, including the ones VS Code
 * started in a directory it picked. The server's `getCwd` takes the launch
 * config's `cwd` first, which is the folder right-clicked in the explorer or the
 * one an extension passed to `createTerminal`, then `terminal.integrated.cwd`,
 * and only then the active workspace folder. An unconditional `cd` here overrode
 * all three, so a terminal opened on a folder was moved somewhere else before
 * the user ever saw a prompt.
 *
 * There is no remembered folder here, and that is the point rather than an
 * omission. This block runs only when no workspace folder is open, so a folder
 * remembered from an earlier session is by construction not the one the user was
 * working in. The v1 shape read `~/.vscodroid_folder`, which was written only
 * when a device folder was opened through the picker and was never cleared, so a
 * device that had opened one once sent every later empty-window shell into that
 * mirror, where a write reaches nothing until the folder is opened again.
 * `~/projects` is where the welcome file already tells the user new terminals
 * start.
 *
 * `HOME` is the one directory the server never picks on purpose: it is the last
 * fallback in `getCwd`, reached only when no workspace folder is open. Landing
 * there is therefore the signal that nobody chose, and that this block still has
 * a job to do.
 *
 * `-ef` rather than `=`, because it compares device and inode instead of text.
 * `${'$'}PWD` comes from `getcwd()` in the child while `${'$'}HOME` is the string this app
 * exported, and the guard must not turn on whether the two spell one directory
 * the same way. It is a bash builtin, so it costs no process, and `.bashrc` is
 * already bash-only.
 *
 * Concatenated after `trimIndent()` rather than written inside the raw string
 * above, so its indentation is fixed here and cannot be re-flowed by a later
 * edit to the lines around it. [ensureStartupDirGuard] matches this text byte
 * for byte on devices that already have a `.bashrc`.
 */
private const val STARTUP_DIR_VERSION = "v2"

private const val STARTUP_DIR_BEGIN = "# >>> vscodroid startup dir"

private const val STARTUP_DIR_END = "# <<< vscodroid startup dir"

private const val STARTUP_DIR_MARKER_CURRENT = "$STARTUP_DIR_BEGIN $STARTUP_DIR_VERSION >>>"


private val STARTUP_DIR_BLOCK = """
    $STARTUP_DIR_MARKER_CURRENT
    # Start in the projects directory ONLY when this shell was given no directory
    # of its own. See FirstRunSetup.ensureStartupDirGuard for why the test is -ef.
    if [ "${'$'}PWD" -ef "${'$'}HOME" ]; then
        cd "${'$'}PROJECTS_DIR" 2>/dev/null || true
    fi
    $STARTUP_DIR_END $STARTUP_DIR_VERSION <<<
""".trimIndent()


/**
 * The block as every release before this one wrote it, byte for byte.
 *
 * Frozen, and never to be regenerated from [STARTUP_DIR_BLOCK]. It is what sits
 * on every device that already has the app, and matching it exactly is what
 * makes the migration safe: a `.bashrc` whose block the user edited matches this
 * nowhere and is left exactly as they wrote it. Deriving it from the new block
 * would disarm that on the first edit to the new one.
 */
/**
 * The v1 block, byte for byte, markers written out rather than composed.
 *
 * Frozen for the reason [LEGACY_STARTUP_DIR_BLOCK] gives, and the markers are
 * literals here rather than `$STARTUP_DIR_BEGIN` and the version constant, so
 * that bumping the version cannot quietly rewrite what this is supposed to
 * match. An exact match is the whole mechanism: a block the user edited matches
 * nothing here and is left as they wrote it.
 */
private val LEGACY_STARTUP_DIR_BLOCK_V1 = """
    # >>> vscodroid startup dir v1 >>>
    # Start in the active folder ONLY when this shell was given no directory of
    # its own. See FirstRunSetup.ensureStartupDirGuard for why the test is -ef.
    if [ "${'$'}PWD" -ef "${'$'}HOME" ]; then
        if [ -f "${'$'}HOME/.vscodroid_folder" ]; then
            __folder="${'$'}(cat "${'$'}HOME/.vscodroid_folder" 2>/dev/null)"
            [ -d "${'$'}__folder" ] && cd "${'$'}__folder" 2>/dev/null || cd "${'$'}PROJECTS_DIR" 2>/dev/null || true
            unset __folder
        else
            cd "${'$'}PROJECTS_DIR" 2>/dev/null || true
        fi
    fi
    # <<< vscodroid startup dir v1 <<<
""".trimIndent()


private val LEGACY_STARTUP_DIR_BLOCK = """
    # Start in the active folder (SAF or default projects dir)
    if [ -f "${'$'}HOME/.vscodroid_folder" ]; then
        __folder="${'$'}(cat "${'$'}HOME/.vscodroid_folder" 2>/dev/null)"
        [ -d "${'$'}__folder" ] && cd "${'$'}__folder" 2>/dev/null || cd "${'$'}PROJECTS_DIR" 2>/dev/null || true
        unset __folder
    else
        cd "${'$'}PROJECTS_DIR" 2>/dev/null || true
    fi
""".trimIndent()


private const val PROMPT_VERSION = "v3"

private const val PROMPT_BEGIN = "# >>> vscodroid prompt"

private const val PROMPT_END = "# <<< vscodroid prompt"

private const val PROMPT_MARKER_CURRENT = "$PROMPT_BEGIN $PROMPT_VERSION >>>"


/**
 * The prompt block written into `.bashrc`, shared by the first-run write and by
 * [FirstRunSetup.ensurePromptFix], which replaces the legacy empty-PS1 prompt.
 */
private val PROMPT_BLOCK = """
    $PROMPT_MARKER_CURRENT
    # PROMPT_COMMAND computes the directory, PS1 renders it. The \[ \] markers tell
    # readline which bytes take no width; without them Ctrl+L and any wrapped line
    # redraw over the prompt. An earlier build printed the prompt straight out of
    # PROMPT_COMMAND with an empty PS1, dating from when the terminal was a pipe
    # rather than a PTY: readline could not measure that at all, and VS Code's
    # shell integration ended up wrapping an empty string.
    __vscodroid_prompt() {
        local dir="${'$'}PWD"
        # The tilde must be escaped. bash expands tildes in a substitution's
        # replacement text, so a bare one turns back into the home path and the
        # whole substitution collapses into a no-op. bash 3.2 does not do this,
        # so a macOS shell cannot reproduce it; only a device can.
        dir="${'$'}{dir/#${'$'}HOME/\~}"
        [[ "${'$'}dir" == /* ]] && dir="${'$'}{dir/#${'$'}PROJECTS_DIR/projects}"
        # Abbreviate SAF mirror paths: /data/.../saf-mirrors/<hash>/... → [saf]/...
        # At the mirror root there is nothing after the hash, so stripping has to be
        # conditional: stripping unconditionally leaves the hash itself standing,
        # which is the one thing this abbreviation exists to hide.
        if [[ "${'$'}dir" == *saf-mirrors/* ]]; then
            dir="${'$'}{dir#*saf-mirrors/}"
            # A folder opened by its name is the link by-name/<hash>/<name>, and a
            # terminal there keeps that spelling: node-pty exports PWD as the
            # directory it starts the shell in, and bash keeps an inherited PWD that
            # names its directory. Dropping by-name/<hash>/ leaves <name>/..., which
            # the case below reads as it reads <hash>/..., so both paths prompt alike.
            dir="${'$'}{dir#by-name/*/}"
            case "${'$'}dir" in
                */*) dir="[saf]/${'$'}{dir#*/}" ;;
                *)   dir="[saf]" ;;
            esac
        fi
        __vscodroid_dir="${'$'}dir"
    }
    PROMPT_COMMAND=__vscodroid_prompt
    PS1='\[\033[32m\]${'$'}{__vscodroid_dir}\[\033[0m\] \${'$'} '
    $PROMPT_END $PROMPT_VERSION <<<
""".trimIndent()


/**
 * Anchors for a prompt block written before the versioned markers existed: the
 * shape shipped in v1.0.0, which printed from PROMPT_COMMAND with an empty PS1.
 */
private const val PROMPT_ANCHOR_START = "__vscodroid_prompt() {"

private const val PROMPT_ANCHOR_END = "PS1=''"

private const val LEGACY_PROMPT_COMMENT = "# Prompt via PROMPT_COMMAND"
