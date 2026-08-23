package com.vscodroid.util

import android.content.Context
import android.net.Uri
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

        // Merge toolchain env vars (GOROOT, JAVA_HOME, etc.)
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
        // Absent for anyone who has installed no toolchain: the generator only
        // creates it when there is something to put in it, and a PATH entry that
        // does not exist costs one failed lookup per command.
        val basePath = "$nativeLibDir:${getTrampolineBinDir(context)}:$filesDir/usr/bin"
        val path = if (extraPath != null)
            "$basePath:$extraPath:/system/bin"
        else
            "$basePath:/system/bin"

        // Preload script that selectively fixes process.platform ("android" → "linux")
        // for npm/node-gyp only. Build tools like Rollup/esbuild see real "android" platform.
        // Loaded in all Node.js processes via NODE_OPTIONS but only activates with opt-in env var.
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
            "PYTHONDONTWRITEBYTECODE" to "1",
            "GIT_EXEC_PATH" to "$filesDir/usr/lib/git-core",
            "GIT_TEMPLATE_DIR" to "$filesDir/usr/share/git-core/templates",
            "GIT_SSH_COMMAND" to "$nativeLibDir/libssh.so -F $homeDir/.ssh/config",
            "GIT_SSL_CAPATH" to getSystemCaCertsPath(),
            // The bundle file curl actually reads. Its Termux build looks for
            // one at a path that does not exist here, and fails before checking
            // any certificate; CAPATH alone does not satisfy it, measured on
            // device. setupGitCaBundle() writes this on every launch it has
            // changed, from the system trust store plus any CA the device owner
            // installed themselves through Settings. That second half reaches
            // git and nothing else: SSL_CERT_DIR below names the system store
            // directly, so python, ruby and curl still see system roots only,
            // and the WebView and the toolchain downloader go through the
            // platform trust manager, which this file cannot influence.
            "GIT_SSL_CAINFO" to "$filesDir/usr/etc/tls/cert.pem",
            "SSL_CERT_DIR" to getSystemCaCertsPath(),
            "NPM_CONFIG_PREFIX" to "$filesDir/usr",
            "NPM_CONFIG_CACHE" to "$cacheDir/npm-cache",
            "PROJECTS_DIR" to getProjectsDir(context),
            // The Claude Code CLI otherwise looks for a ripgrep under its own
            // vendor/<arch>-<platform>/, a directory that cannot exist here,
            // since process.platform reports "android" and the builds shipped
            // are for glibc and musl. Unset, it finds nothing and searching
            // fails with no explanation. Falsy sends it to `rg` on PATH, which
            // is the Bionic build already bundled as libripgrep.so.
            "USE_BUILTIN_RIPGREP" to "0",
            "VSCODROID_PORT" to port.toString(),
            "VSCODROID_VERSION" to getVersionName(context),
        )

        return base + toolchainEnv
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

    fun getProjectsDir(context: Context): String {
        // App-external storage, which needs no permission.
        // Path: /storage/emulated/0/Android/data/<pkg>/files/projects
        //
        // "visible in file managers" is what this comment claimed until
        // 2026-08-16, and it is not something to rely on. Android 11 closed
        // Android/data to other apps and to the system Files app, and minSdk
        // here is 33, so no supported device has the unrestricted access the
        // claim assumed. Some routes remain (MTP over USB, a few OEM managers),
        // which is how the projects folder gets deleted from outside at all;
        // see the CHANGELOG entry about surviving exactly that.
        //
        // The consequence that matters is the one for Clear Data: it wipes this
        // directory, and a user cannot count on being able to copy anything out
        // first. docs/USER_GUIDE.md carries the warning and the rescue steps
        // where it recommends clearing. Work that has to stay reachable from
        // outside the app belongs in a folder opened through the SAF picker.
        val externalDir = context.getExternalFilesDir(null)
        return if (externalDir != null) {
            File(externalDir, "projects").absolutePath
        } else {
            // Fallback to internal storage if external unavailable
            "${context.filesDir}/home/projects"
        }
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
     * Only REMOTE_MACHINE_SCOPES are taken from it: MACHINE, WINDOW, RESOURCE,
     * LANGUAGE_OVERRIDABLE, MACHINE_OVERRIDABLE (`configuration.ts:387`). An
     * APPLICATION-scoped setting is still ignored by the WEB CLIENT here no
     * matter how correct the path is, which is why Workspace Trust needs the
     * server's CLI flag.
     *
     * The web client is not the only reader, and the difference decides which
     * defaults are worth writing. The server builds its own ConfigurationService
     * on this same file with an empty options object, so it takes every key
     * whatever the scope: that is why `extensions.verifySignature`, which is
     * APPLICATION-scoped and which only the server reads, does take effect. A
     * key that is APPLICATION-scoped AND read only by the workbench cannot be
     * defaulted from here at all, and the file says nothing when one is dropped.
     *
     * Settings the user edits in the workbench go to IndexedDB in the WebView
     * instead, and take precedence over this file. That is the right order: these
     * are defaults, not overrides.
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

    fun getGitPath(context: Context): String =
        "${context.applicationInfo.nativeLibraryDir}/libgit.so"

    /**
     * musl's loader, and the only way the Claude Code CLI starts here.
     *
     * The CLI is a musl binary the user's extension brings with it, sitting under
     * filesDir where SELinux refuses execve() for targetSdk >= 29. It does allow
     * map and execute, which is all a loader needs, so the loader is execve'd
     * from nativeLibraryDir -- the one directory an app may execute from -- and
     * mmaps the CLI out of filesDir itself.
     *
     * It is named as claudeCode.claudeProcessWrapper directly rather than through
     * a shim, because resolveClaudeBinary() passes the resolved binary path as the
     * wrapper's first argument, which is already how a loader expects to be
     * called. The glibc build the marketplace would otherwise serve cannot be
     * loaded at all: its startup calls set_robust_list and rseq, and Android's
     * app seccomp filter kills the process for either. Patch 0009 is what makes
     * the marketplace hand over the musl build instead.
     */
    fun getMuslLoaderPath(context: Context): String =
        "${context.applicationInfo.nativeLibraryDir}/libldmusl.so"

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
