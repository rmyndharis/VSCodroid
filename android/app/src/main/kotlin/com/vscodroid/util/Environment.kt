package com.vscodroid.util

import android.content.Context
import android.net.Uri
import android.system.Os
import com.vscodroid.setup.ToolchainManager
import java.io.File
import java.security.MessageDigest

object Environment {

    fun buildProcessEnvironment(context: Context, port: Int): Map<String, String> {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val filesDir = context.filesDir.absolutePath
        val cacheDir = context.cacheDir.absolutePath
        val homeDir = "$filesDir/home"

        // Use bundled bash if available, otherwise fall back to system shell.
        //
        // SHELL names the usr/bin/bash symlink rather than the .so it points at,
        // and that indirection is what makes shell integration possible at all.
        // The ptyHost picks the injection arguments from a table keyed by the
        // executable's *basename*: `bash` maps to
        // ["--init-file", "{0}/shellIntegration-bash.sh"], `libbash.so` matches
        // nothing. The indirection pays twice, because setupToolSymlinks()
        // re-points the link on every launch, so a reinstall that moves
        // nativeLibraryDir cannot leave it dangling.
        //
        // SHELL is what a terminal falls back to when no profile supplies a
        // default, so the basename has to be right on that path too, but it is
        // not the only path. `terminal.integrated.profiles.linux` is read; see
        // createDefaultSettings() in FirstRunSetup for why `linux` is the suffix
        // the workbench looks up on Android.
        val shell = if (File("$nativeLibDir/libbash.so").exists())
            getTerminalShellPath(context)
        else
            "/system/bin/sh"

        // Use xterm-256color for bundled bash (full PTY via node-pty native).
        // Fallback to dumb terminal for system shell (basic compatibility).
        val term = if (File("$nativeLibDir/libbash.so").exists())
            "xterm-256color"
        else
            "dumb"

        // Merge toolchain env vars (JAVA_HOME, RUBYLIB, etc.). Read here once,
        // at server start, and that is all a non-bash child ever inherits from
        // this side. A toolchain installed while the server runs reaches those
        // children through the environment rows
        // ToolchainManager.regenerateExecTableLocked writes into the
        // trampoline's table on every install; bash re-reads toolchain-env.sh
        // on its own.
        val toolchainEnv = getToolchainEnvironment(context)
        val extraPath = toolchainEnv.remove("__TOOLCHAIN_EXTRA_PATH")
        // The trampoline directory sits between the bundled binaries and
        // usr/bin, and both sides of that placement are load-bearing.
        //
        // Ahead of usr/bin, because for a toolchain that installs into usr/bin
        // the two hold the same names: `usr/bin/ruby` IS the Ruby interpreter,
        // an ELF under filesDir that SELinux refuses to execve, and
        // `usr/bin/java` is a symlink onto the JDK's. Whichever comes first is
        // what a bare-name lookup finds, so with usr/bin first the trampoline
        // would never be reached and every programmatic invocation would go on
        // failing with EACCES.
        //
        // Behind nativeLibDir, because that is where bash, node, git and rg
        // live as real executables and no toolchain may shadow them.
        //
        // Present on every device, which it was not until `xdg-open` moved in.
        // The generator used to create this directory only when a toolchain had
        // put something in it, so on a device with none the entry named nothing
        // and cost one failed lookup per command. It now always carries at least
        // the browser opener, which is a command the editor needs whether or not
        // a toolchain was ever installed.
        val basePath = "$nativeLibDir:${getTrampolineBinDir(context)}:$filesDir/usr/bin"
        val path = if (extraPath != null)
            "$basePath:$extraPath:/system/bin"
        else
            "$basePath:/system/bin"

        // Preload that corrects two platform checks on Android, and nothing else.
        // process.platform reads "linux" only when the npm/npx functions opt in with
        // VSCODROID_PLATFORM_FIX=1 or node's entry script is node-gyp, so
        // Rollup/esbuild still see "android". os.platform() reads "linux" only inside
        // the Jupyter extension's bundle, with no opt-in, so its pidtree can signal a
        // kernel's children. VS Code deletes NODE_OPTIONS from the extension host's
        // environment: server.js passes the preload in the editor server's execArgv,
        // which the extension host and the Node children it forks inherit, and
        // BASH_ENV puts NODE_OPTIONS back for a shell the extension host starts.
        val platformFixPath = "$filesDir/server/platform-fix.js"
        val nodeOptions = "--require=$platformFixPath"

        // The Termux tmux searches "$TMUX_TMPDIR:/data/data/com.termux/files/usr/var/run"
        // for its socket. That second path belongs to Termux's sandbox, not ours, so
        // without the variable every session dies with "no suitable socket path".
        val tmpDir = "$cacheDir/tmp"

        val base = mapOf(
            "HOME" to homeDir,
            "TMPDIR" to tmpDir,
            "TMUX_TMPDIR" to tmpDir,
            "PATH" to path,
            "LD_LIBRARY_PATH" to "$nativeLibDir:$filesDir/usr/lib",
            // What the exec interceptor reads to tell the app's own paths from
            // the system's, once a terminal preloads it (see
            // [getExecPreloadPath]). Inert until then: no bundled ELF reads any
            // of these, so they change nothing for the server, the extension
            // host or a language server. They sit here rather than beside
            // LD_PRELOAD in the terminal setting because this map is the base of
            // every terminal, task and extension spawn, so the day the preload
            // is widened past the terminal it is one row and no settings edit.
            //
            // Both data-dir spellings are load-bearing. The interceptor decides
            // from these which files are the app's, and the kernel reports the
            // working directory as /data/data/<pkg> while applicationInfo says
            // /data/user/0/<pkg>. With the legacy row unset every relative exec
            // of a filesDir ELF was refused with 126, however the caller spelled
            // it, measured on API 33 and 36 emulators, 2026-09-22/23.
            //
            // LD_PRELOAD itself is deliberately NOT here. It reaches terminals
            // and tasks through terminal.integrated.env.linux, which is where
            // the interception has been measured; from this map it would load
            // under node itself and everything node forks, which has not.
            // ExecPreloadEnvTest pins that absence.
            "TERMUX_APP__DATA_DIR" to context.applicationInfo.dataDir,
            "TERMUX_APP__LEGACY_DATA_DIR" to "/data/data/${context.packageName}",
            "TERMUX__PREFIX" to "$filesDir/usr",
            "NODE_PATH" to "$filesDir/server/vscode-reh/node_modules",
            "NODE_OPTIONS" to nodeOptions,
            "SHELL" to shell,
            // What a NON-interactive bash reads at startup, and the only way the
            // bundled commands exist for one. npm, npx, claude and every
            // toolchain binary are bash FUNCTIONS, not files: SELinux denies
            // execve under filesDir, so there is nothing on PATH for a plain
            // `npm` to find. Those functions were written into .bashrc alone,
            // which bash reads only when interactive -- so a VS Code task, an
            // npm lifecycle script, or anything an extension runs through
            // `bash -c` got "command not found" for a command the terminal
            // beside it runs fine.
            //
            // Measured against bash 3.2.57, and it is the shape of the rule
            // rather than the version that matters: `bash -c`, `bash script.sh`
            // and `bash -lc` all source this file; an interactive shell does
            // not. `bash -lc` also reads .bashrc, through .bash_profile, so the
            // two files overlap there and everything in this one has to be safe
            // to run twice. What it does NOT reach is written out at
            // [FirstRunSetup.createBashEnvFile].
            "BASH_ENV" to getBashEnvPath(context),
            // Where the execution trampoline finds out which program a command
            // name means. It is a plain table rather than a shell file because
            // its readers are not shells: a direct execve from an extension,
            // mksh running a make recipe, or a "type": "process" task. See
            // [getExecTablePath] for why it is exported rather than compiled in.
            "VSCODROID_EXEC_TABLE" to getExecTablePath(context),
            "TERM" to term,
            "TERMINFO" to "$filesDir/usr/share/terminfo",
            "LANG" to "en_US.UTF-8",
            "PREFIX" to "$filesDir/usr",
            "PYTHONHOME" to "$filesDir/usr",
            // Where Python may write bytecode, instead of the
            // PYTHONDONTWRITEBYTECODE=1 that stood here and taxed every import.
            //
            // The shipped tree carries no `.pyc`: download-python.sh strips
            // `__pycache__` from the stdlib and from pip. With writing refused as
            // well, every import re-parsed source on every run. Measured in the
            // app's terminal on an API 33 emulator, five runs averaged, refused
            // against a warm cache: `python3 -c pass` 20ms to 13ms, a
            // four-module stdlib import 90ms to 27ms, `python3 -m pip --version`
            // 328ms to 97ms. pip, the language server and the Jupyter kernel are
            // all Python and all paid it.
            //
            // A prefix rather than bytecode written in place, which would land in
            // the extracted asset tree and beside the user's own sources. Under
            // cacheDir it is out of both, and Android may reclaim it, which costs
            // one slow run. It does grow: the prefix mirrors every source path and
            // is never pruned by Python, so [StorageManager.pruneTemporaryBytecode]
            // drops what temporary build trees left, and
            // [StorageManager.clearCaches] deletes the whole directory by name.
            // pip records a package's bytecode here too, outside a venv's prefix,
            // so `pip uninstall` in a venv lists those files as ones it will not
            // remove; they go with the cache.
            "PYTHONPYCACHEPREFIX" to "$cacheDir/pycache",
            "GIT_EXEC_PATH" to "$filesDir/usr/lib/git-core",
            "GIT_TEMPLATE_DIR" to "$filesDir/usr/share/git-core/templates",
            "GIT_SSH_COMMAND" to "$nativeLibDir/libssh.so -F $homeDir/.ssh/config",
            "GIT_SSL_CAPATH" to getSystemCaCertsPath(),
            // The bundle file curl actually reads. Its Termux build looks for
            // one at a path that does not exist here, and fails before checking
            // any certificate; CAPATH alone does not satisfy it, measured on
            // device. setupGitCaBundle() writes this on every launch it has
            // changed, from the system trust store plus any CA the device owner
            // installed themselves through Settings.
            "GIT_SSL_CAINFO" to "$filesDir/usr/etc/tls/cert.pem",
            // The same bundle for everything else that links OpenSSL, and this
            // used to say the directory below was enough for them. It is not,
            // and the reason is a hash algorithm.
            //
            // A directory trust store is not scanned, it is looked up: OpenSSL
            // hashes the issuer name and opens `<hash>.0`. Android names its
            // files with the OpenSSL 0.9.8 hash, and everything since 1.0 uses a
            // different one. Measured against a cert out of
            // /system/etc/security/cacerts on the emulator: the file is
            // 01419da9.0, `openssl x509 -subject_hash_old` answers 01419da9, and
            // `-hash` answers 8d89cda1, which is the name OpenSSL 3 goes looking
            // for and which is not there. So SSL_CERT_DIR alone leaves a store
            // that can be listed and never read.
            //
            // What that cost, measured on device before this line existed: the
            // bundled Python loaded zero certificates and `urllib.request` on
            // https://pypi.org failed with CERTIFICATE_VERIFY_FAILED. With the
            // file named here it loads 120 and the same request answers 200.
            // Ruby's OpenSSL reads the same variable and was in the same state.
            //
            // pip is NOT the client this rescues, and saying so was the first
            // wrong version of this comment: it verifies against the certifi
            // bundle vendored inside itself, so it worked throughout. What was
            // broken is everything that asks OpenSSL for the default trust,
            // which is any ordinary script a user writes.
            //
            // The directory stays beside it. It costs nothing, and a client that
            // does resolve the old hashes keeps working.
            "SSL_CERT_FILE" to "$filesDir/usr/etc/tls/cert.pem",
            "SSL_CERT_DIR" to getSystemCaCertsPath(),
            // The configuration file libcrypto loads at start, named here
            // because the default is Termux's. The bundled libcrypto carries
            // OPENSSLDIR /data/data/com.termux/files/usr/etc/tls compiled in,
            // so with nothing said it opens that directory's openssl.cnf in
            // every process. On a device without Termux that open fails with
            // ENOENT, which OpenSSL ignores, and nobody noticed. On a device
            // where Termux has run, the directory exists and belongs to
            // another app, the open fails with EACCES, and Node treats any
            // error but a missing file as fatal: "OpenSSL configuration
            // error: ... BIO_new_file:Permission denied ... calling
            // fopen(/data/data/com.termux/files/usr/etc/tls/openssl.cnf)"
            // before main(), on all six attempts, and the server-gave-up page
            // is what the user sees (issue #447, the reporter's own
            // server.log). The file this names is written on every launch by
            // FirstRunSetup.setupOpensslConfig, beside the CA bundle above.
            "OPENSSL_CONF" to "$filesDir/usr/etc/tls/openssl.cnf",
            "NPM_CONFIG_PREFIX" to "$filesDir/usr",
            "NPM_CONFIG_CACHE" to "$cacheDir/npm-cache",
            // Beside npm's, for the same reason: a cache Clear Caches can empty and
            // Android can reclaim. Unset, pip keeps downloaded wheels in
            // ~/.cache/pip, under files, where neither reaches them.
            "PIP_CACHE_DIR" to "$cacheDir/pip",
            "PROJECTS_DIR" to getProjectsDir(context),
            // The Claude Code CLI otherwise looks for a ripgrep under its own
            // vendor/<arch>-<platform>/, a directory that cannot exist here,
            // since process.platform reports "android" and the builds shipped
            // are for glibc and musl. Unset, it finds nothing and searching
            // fails with no explanation. Falsy sends it to `rg` on PATH, which
            // is the statically linked build already bundled as libripgrep.so.
            "USE_BUILTIN_RIPGREP" to "0",
            // Where the Jupyter extension from Open VSX finds a zeromq addon it
            // can load. Its own prebuilds are for glibc and musl, so without this
            // it cannot talk to a kernel directly and falls back to a Jupyter
            // server that cannot be installed or started here. Its loader
            // (@aminya/node-gyp-build) replaces a package's directory with
            // process.env[<NAME>_PREBUILD] and takes build/Release/*.node from
            // it. Under filesDir rather than in the extension's directory, which
            // an extension update replaces; scripts/build-native-addons.sh
            // builds it. Inherited by every process the server starts, terminals
            // included, where it redirects any zeromq 5.x or 6.0.x a project loads,
            // even one built from source; zeromq 6.1 and later load through
            // cmake-ts and ignore it.
            "ZEROMQ_PREBUILD" to "$filesDir/usr/lib/node-addons/zeromq",
            "VSCODROID_PORT" to port.toString(),
            "VSCODROID_VERSION" to getVersionName(context),
            // server.js pins the sign-in callback intent to it. packageName and
            // not a literal, because a debug build carries the `.debug` suffix.
            "VSCODROID_PACKAGE" to context.packageName,
        )

        // The name pip actually reads, and what makes a CA the device owner
        // installed count for a private index. It is requests' variable rather
        // than pip's own PIP_CERT, so the one row also reaches a user's own
        // `requests` code.
        //
        // Conditional, and the guard is the whole reason this is not a row in
        // the map above. requests treats a bundle it cannot open as fatal
        // instead of falling back to its vendored certifi: measured on an API 37
        // emulator, pointing it at a missing path stops pip with "Could not find
        // a suitable TLS CA certificate bundle". setupGitCaBundle() gives up on
        // a device with no system CA directory and after a failed write, so
        // naming a file it never wrote would take away the one Python HTTPS
        // client that works without any of this. SSL_CERT_FILE above wants no
        // such guard: with a path that does not exist OpenSSL loads nothing and
        // verification fails, which is where this started rather than worse.
        val caBundle = File("$filesDir/usr/etc/tls/cert.pem")
        val requestsCa =
            if (caBundle.isFile) mapOf("REQUESTS_CA_BUNDLE" to caBundle.absolutePath)
            else emptyMap()

        return base + requestsCa + toolchainEnv
    }

    private fun getToolchainEnvironment(context: Context): MutableMap<String, String> {
        return try {
            ToolchainManager(context).getAllToolchainEnv().toMutableMap()
        } catch (e: Exception) {
            // Toolchain state file may not exist yet, not an error
            mutableMapOf()
        }
    }

    fun getNodePath(context: Context): String =
        "${context.applicationInfo.nativeLibraryDir}/libnode.so"

    fun getServerScript(context: Context): String =
        "${context.filesDir}/server/server.js"

    /**
     * The default workspace: internal storage, unless this install already put
     * the user's work on shared storage.
     *
     * It used to be `getExternalFilesDir(null)/projects` for everyone, chosen so
     * that a file manager could reach the code. That reachability is gone:
     * Android 11 closed `Android/data` to other apps and to the system Files
     * app, and minSdk here is 33, so no supported device has it. A few routes
     * remain (MTP over USB, some OEM managers), which is how the folder gets
     * deleted from outside at all, but nothing a user can rely on.
     *
     * What did not go away is the filesystem. Shared storage is served through
     * FUSE, which does not implement `symlink(2)` at all, so every symlink an
     * ordinary toolchain writes fails with EPERM: measured on an API 37
     * emulator, `ln -s` under `Android/data/<pkg>/files/projects` answers
     * "Permission denied" while the same call under `filesDir` succeeds. That
     * cost `npm install` any package shipping an executable, because npm writes
     * `node_modules/.bin/<name>` as a link and dies on the first one, and it
     * costs pnpm, a Python venv without `--copies` and any build step that links.
     * The npm failure names a `.bin` path and an EPERM, neither of which a user
     * has any reason to connect to where the folder lives.
     *
     * An install that already has a projects directory on shared storage keeps
     * it, and that is not caution for its own sake: `.bashrc` bakes
     * `PROJECTS_DIR` in when it is first written and nothing rewrites it, so an
     * answer that moved under an existing install would leave every terminal
     * starting somewhere the editor is not. Moving the user's own files is not
     * something to do behind their back either. The directory's own existence is
     * the record, because nothing creates it any more: a fresh install never has
     * one and an install that does can only have got it from a release where it
     * was the default.
     *
     * Clear Data still wipes whichever of the two is in use, and work that has to
     * stay reachable from outside the app still belongs in a folder opened
     * through the SAF picker. Neither of those changed with the location.
     */
    fun getProjectsDir(context: Context): String {
        val filesDir = context.filesDir.absolutePath
        val internal = "$filesDir/projects"
        val externalDir = context.getExternalFilesDir(null) ?: return internal
        val legacy = File(externalDir, "projects")
        if (legacy.isDirectory) return legacy.absolutePath
        // Answered before the link is read, so the ordinary case costs one stat
        // and no syscall: once either directory is there, that is the answer.
        if (File(internal).isDirectory) return internal
        // Neither is on disk, which happens twice: on the first launch of a
        // fresh install, and after something outside the app deleted the shared
        // storage directory, which is what ensureProjectsDir() exists to repair.
        // Telling those apart matters, because answering "internal" for the
        // second would move an existing install's workspace on the strength of a
        // deletion, with `.bashrc` still exporting the old path. `~/projects` is
        // written beside the directory and outlives it, so its target is the
        // record of which one this install has been using. Os.readlink throws off
        // a device and on anything that is not a link, and both mean the same
        // thing here: no such record.
        val link = runCatching { Os.readlink("$filesDir/home/projects") }.getOrNull()
        return if (link == legacy.absolutePath) legacy.absolutePath else internal
    }

    fun getHomeDir(context: Context): String =
        "${context.filesDir}/home"

    /**
     * Where the SSH identity lives.
     *
     * Named here rather than composed at each call site because callers now
     * include ones that must exclude it rather than reach into it: the key is
     * generated without a passphrase for mobile UX, so any code deciding what
     * may be read has to be able to say "not this directory" without writing
     * the path out and going stale when the layout moves.
     */
    fun getSshDir(context: Context): String =
        "${getHomeDir(context)}/.ssh"

    fun getUserDataDir(context: Context): String =
        "${context.filesDir}/home/.vscodroid"

    /**
     * The settings file the workbench actually reads from this side.
     *
     * Not `<user-data-dir>/User/settings.json`, which is what this app wrote for
     * its first year and which nothing has ever read. The path is derived in three
     * steps, none of them where you would look first:
     * `server.main.ts:39-40` sets `USER_DATA_PATH = <server-data-dir>/data`,
     * ignoring `--user-data-dir` entirely; `environmentService.ts:86` puts machine
     * settings at `<USER_DATA_PATH>/Machine/settings.json`; and
     * `remoteAgentEnvironmentImpl.ts:112` hands exactly that to the client as the
     * remote `settingsPath`.
     *
     * Only REMOTE_MACHINE_SCOPES are taken from it: MACHINE, APPLICATION_MACHINE,
     * WINDOW, RESOURCE, LANGUAGE_OVERRIDABLE and MACHINE_OVERRIDABLE, every scope
     * except APPLICATION. An APPLICATION-scoped setting is still ignored by the
     * WEB CLIENT here no matter how correct the path is, which is why Workspace
     * Trust needs the server's CLI flag.
     *
     * There are three readers, not two, and the differences decide what may be
     * written here. The server builds its own ConfigurationService on this same
     * file with an empty options object, so it takes every key whatever the
     * scope: that is why `extensions.verifySignature`, which is APPLICATION
     * scoped and which only the server reads, does take effect. A key that is
     * APPLICATION-scoped AND read only by the workbench cannot be defaulted from
     * here at all, and the file says nothing when one is dropped. The third is
     * `ProcessManager`, which reads `vscodroid.server.heapCeilingMb` out of the
     * same document before the server starts.
     *
     * ⚠️ **This is not a defaults file, and this paragraph said it was.** The
     * workbench parses it as the REMOTE USER settings and merges it ON TOP of the
     * user's own settings: the consolidated order is default < application <
     * user-local < THIS FILE < workspace < memory. The user's own settings are
     * `vscode-userdata:/User/settings.json`, which in a web workbench lives in
     * the WebView's IndexedDB, so neither this app nor the terminal can see or
     * repair them. The Settings editor opens on the User tab and writes there, so
     * a preference written into this file beat whatever the user had just
     * changed, with no error and only a small "also modified in remote" hint to
     * show for it.
     *
     * So a preference does not belong here. It belongs in
     * `contributes.configurationDefaults` in the bundled welcome extension, which
     * lands in the DEFAULT layer below every user file. What belongs here is what
     * the user has no business overriding and no way to express: paths that move
     * on every reinstall, keys only the server reads, and facts about the device.
     */
    fun getMachineSettingsPath(context: Context): String =
        "${getUserDataDir(context)}/data/Machine/settings.json"

    /**
     * The file the server keeps its connection token in.
     *
     * Under `data/`, not directly under the user-data dir, and for exactly the
     * reason [getMachineSettingsPath] documents: `server.main.ts:39-40` sets
     * `USER_DATA_PATH = <server-data-dir>/data` and the token resolver reads the
     * already-rewritten value, so `--user-data-dir` never names this path
     * itself. Deriving it by hand from that flag puts it one directory too high,
     * where nothing writes it -- the same shape of mistake as writing settings to
     * `User/settings.json`.
     *
     * The server creates it with mode 0600 on first start and reuses it after
     * that, so it is stable across server restarts and app updates.
     */
    fun getConnectionTokenPath(context: Context): String =
        "${getUserDataDir(context)}/data/token"

    /**
     * The file `BASH_ENV` names, written by [FirstRunSetup.createBashEnvFile].
     *
     * Beside `toolchain-env.sh` rather than in `home/`, because it is generated
     * state and not something a user edits: it is rewritten whole whenever its
     * contents change. `.bashrc` stays the interactive shell's file and is
     * appended to, never regenerated.
     */
    fun getBashEnvPath(context: Context): String =
        "${getUserDataDir(context)}/bash-env.sh"

    /**
     * The table the execution trampoline reads, written by
     * [ToolchainManager.regenerateExecTableLocked].
     *
     * Named here rather than spelled out at each end, because the two ends are
     * a Kotlin writer and a C reader that share nothing but this string. A
     * writer that moved the file, or an exported variable that named the old
     * path, would each leave every toolchain command answering exit 127 with
     * every test about the table's CONTENT still green. `EnvironmentPathOrderTest`
     * holds the two together.
     *
     * The path reaches the trampoline through the environment rather than being
     * compiled into it, so a build that moves the user-data directory does not
     * need a new native binary; the trampoline also derives it from `PREFIX`
     * when the variable has been scrubbed, which is the same relationship
     * `toolchain-env.sh` already uses.
     */
    fun getExecTablePath(context: Context): String =
        "${getUserDataDir(context)}/toolchain-exec.tsv"

    /**
     * The directory of trampoline symlinks that puts toolchain commands on PATH.
     *
     * Deliberately NOT `usr/bin`. For a toolchain that installs into `usr/bin`
     * the interpreter itself is there under the command's own name, so a
     * trampoline link written into that directory would overwrite the very
     * binary the table points at. `usr/bin` is also written by three other
     * passes (`setupToolSymlinks`, `installFromDirectory` and
     * `createNpmWrappers`), and this directory belongs to one generator that
     * sweeps whatever it does not recognise.
     */
    fun getTrampolineBinDir(context: Context): String =
        "${context.filesDir}/usr/libexec/tcbin"

    /**
     * The trampoline binary itself, in the one directory this app may execve.
     *
     * Built by `scripts/build-exec-trampoline.sh` into `jniLibs/arm64-v8a`, so
     * the package manager extracts it here with the execute bit. Every link in
     * [getTrampolineBinDir] resolves to this one file; the command it should
     * start is decided from `argv[0]` and the table, because a program reached
     * through a symlink cannot learn which link invoked it (measured on
     * emulator-5554, API 33: `argv[0]` is the bare name asked for and
     * `/proc/self/exe` resolves all the way through to the shared binary).
     */
    fun getTrampolinePath(context: Context): String =
        "${context.applicationInfo.nativeLibraryDir}/libexec-trampoline.so"

    fun getExtensionsDir(context: Context): String =
        "${context.filesDir}/home/.vscodroid/extensions"

    fun getLogsDir(context: Context): String =
        "${context.filesDir}/home/.vscodroid/data/logs"

    fun getServerDir(context: Context): String =
        "${context.filesDir}/server"

    /**
     * The bash binary itself, under `nativeLibraryDir`.
     *
     * ⚠️ Has no production caller, and that is the point rather than an oversight,
     * so a sweep for unused code should leave it here. It is the wrong answer for
     * the terminal profile and [getTerminalShellPath] below is the right one; the
     * two exist side by side so the difference is visible at the place someone
     * would reach for either. `TerminalShellPathTest` is built on that contrast.
     *
     * Production that genuinely wants the binary spells it out at the point of use
     * (`FirstRunSetup.createNpmWrappers`), because it wants the `.so` knowingly.
     */
    fun getBashPath(context: Context): String =
        "${context.applicationInfo.nativeLibraryDir}/libbash.so"

    /**
     * The shell to name in the terminal profile: the maintained symlink, never
     * the `nativeLibraryDir` binary it points at.
     *
     * VS Code decides whether it can inject shell integration by switching on the
     * *basename* of the profile's executable. `libbash.so` matches no case and the
     * injection is skipped in silence; `bash` matches. The indirection pays twice,
     * because `setupToolSymlinks()` re-points this link on every launch, so the
     * profile no longer goes stale when a reinstall moves `nativeLibraryDir`.
     */
    fun getTerminalShellPath(context: Context): String =
        "${context.filesDir}/usr/bin/bash"

    /**
     * The exec interceptor's place under `usr/lib`, the same string relative to
     * `assets/` and to filesDir, so the extraction and [getExecPreloadPath] name
     * one file.
     */
    const val EXEC_PRELOAD_ASSET = "usr/lib/libtermux-exec.so"

    /**
     * The exec interceptor a terminal preloads, and the one LD_PRELOAD value
     * that can be written into a setting.
     *
     * It is a Bionic library that catches every exec a shell makes and starts a
     * file under filesDir, which SELinux refuses to execve, through
     * `/system/bin/linker64` instead. That is what lets `./a.out`, a `#!/bin/sh`
     * git hook and a venv's console script run from the terminal. Built by
     * `scripts/build-termux-exec.sh` into `assets/usr/lib`, extracted with the
     * rest of `usr/` on every version bump, and re-extracted on any launch that
     * finds it missing or the wrong length (`FirstRunSetup.ensureExecPreload`).
     *
     * A real file under filesDir, and nothing else will do, because Bionic
     * treats a preload name like a DT_NEEDED: one it cannot find aborts the
     * exec of every program in that environment, bash and `/system/bin/sh`
     * included, with `CANNOT LINK EXECUTABLE ... library not found` and no
     * warning mode. Measured on API 33 and 36 emulators, 2026-09-22/23, for a
     * missing file and for a dangling link alike: no terminal reaches a prompt
     * until the file is back. So the path must be one that never moves and
     * never dangles. Never `nativeLibraryDir`, which Android renames on every
     * reinstall and which a same-version reinstall reached without Splash
     * leaves stale with no repair; never a symlink into it, which dangles the
     * same way; and never a bare name, which resolves through LD_LIBRARY_PATH
     * and dies the moment a child clears that. What goes stale in a value
     * here is fatal rather than degraded, unlike every other path in the
     * settings file.
     */
    fun getExecPreloadPath(context: Context): String =
        "${context.filesDir}/$EXEC_PRELOAD_ASSET"

    fun getGitPath(context: Context): String =
        "${context.applicationInfo.nativeLibraryDir}/libgit.so"

    /**
     * What `claudeCode.claudeProcessWrapper` names, and what starts the CLI.
     *
     * The CLI is a musl binary the user's extension brings with it, sitting
     * under filesDir where SELinux refuses execve() for targetSdk >= 29. It does
     * allow map and execute, which is all a loader needs, so musl's loader
     * (`libldmusl.so`, beside this) is execve'd from nativeLibraryDir -- the one
     * directory an app may execute from -- and mmaps the CLI out of filesDir
     * itself. The glibc build the marketplace would otherwise serve cannot be
     * loaded at all: its startup calls set_robust_list and rseq, and Android's
     * app seccomp filter kills the process for either. Patch 0009 is what makes
     * the marketplace hand over the musl build instead.
     *
     * The extension hands the wrapper the CLI as its first argument, which is
     * that loader's own calling convention, so the loader used to be named here
     * directly. It cannot be any more: the CLI's runtime calls `epoll_pwait2`,
     * which bionic exposes only from android15, and on android13 and android14
     * the call is refused with SIGSYS rather than an error return, which kills
     * the process unless a handler answers it. The shim that answers that call
     * has to be loaded before the binary runs, and a setting holds a path
     * rather than a loader option, so a launcher sits in between and passes it
     * as the loader's `--preload=`. See `scripts/claude-launch.c`.
     *
     * On that option and deliberately never in LD_PRELOAD. The shim interposes
     * `sigaction` against musl's structure layout, which is not Bionic's, and
     * every child inherits an environment variable: the CLI's own bash, node and
     * git would load it and abort with stack corruption, which was measured.
     * `--preload=` loads it into the one process and nothing below it.
     */
    fun getClaudeLauncherPath(context: Context): String =
        "${context.applicationInfo.nativeLibraryDir}/libclaude-launch.so"

    private fun getSystemCaCertsPath(): String =
        // Android 14+ (APEX module), fallback to legacy path
        if (File("/apex/com.android.conscrypt/cacerts").isDirectory)
            "/apex/com.android.conscrypt/cacerts"
        else
            "/system/etc/security/cacerts"

    private fun getVersionName(context: Context): String =
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }

    // -- SAF (Storage Access Framework) --

    fun getSafMirrorsDir(context: Context): String =
        "${context.filesDir}/saf-mirrors"

    fun getSafMirrorDir(context: Context, safUri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(safUri.toString().toByteArray())
            .take(6) // 6 bytes = 12 hex chars, collision probability ~1 in 281 trillion
            .joinToString("") { "%02x".format(it) }
        return "${getSafMirrorsDir(context)}/$hash"
    }
}
