package com.vscodroid.setup

import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import java.io.File
import java.io.IOException
import java.nio.file.Files

// Machine settings JSONC migration, default settings creation, and pip/OpenSSL config templates.

/**
 * Updates nativeLibraryDir paths in settings.json.
 *
 * Android changes nativeLibraryDir on every reinstall (random hash in path).
 * Settings like terminal.integrated.profiles.linux.bash.path and git.path
 * reference this directory, so they must be refreshed on each launch.
 */
internal fun FirstRunSetup.updateSettingsNativeLibPathsImpl() {
    migrateSettingsToMachinePathImpl()

    val settingsFile = File(Environment.getMachineSettingsPath(context))
    // Read, decided and written under the lock its write takes, as the
    // withdrawal of a restored engine consent is. This runs beside setup
    // and outside its lock, and a withdrawal that landed between the read
    // and the write here was undone by it, for good: nothing withdraws a
    // consent again once setup has completed.
    holdingWriteLock(settingsFile) { refreshSettings(settingsFile) }
}


/** The body of [updateSettingsNativeLibPaths], run under the lock on [settingsFile]. */
internal fun FirstRunSetup.refreshSettings(settingsFile: File) {
    if (!settingsFile.exists()) return

    // Two passes over one document, written once. They are separate
    // functions because they answer to different things: the first repairs
    // paths that go stale on every reinstall, the second fills in layout
    // defaults a device that installed an earlier version never had. Either
    // one alone can be the reason there is something to write.
    val current = settingsFile.readText()
    val refreshed = run {
        val paths = refreshManagedPaths(
            current,
            Environment.getTerminalShellPath(context),
            Environment.getGitPath(context),
            Environment.getClaudeLauncherPath(context),
        )
        // Only once the library is on disk. A preload the linker cannot find
        // aborts every exec in that environment, the shell included, so the
        // line without the file is a dead terminal on every install the
        // build reached; with no file there is nothing to write and the
        // terminal stays as it is today. [ensureExecPreload] runs ahead of
        // this in SplashActivity so an install that has the asset gets the
        // file first and the line in the same launch.
        val preloadPath = Environment.getExecPreloadPath(context)
        val preloaded =
            if (File(preloadPath).isFile) {
                ensureTerminalPreload(paths ?: current, preloadPath, context.packageName)
            } else {
                null
            }
        preloaded ?: paths
    }
    // Once per install, between the two: the preferences this app used to
    // write are taken back out here, and re-reading a document that no
    // longer holds any of them is work with no outcome.
    //
    // The signal is a durable flag, NOT a versionCode comparison, and the
    // difference is the whole point. The gate was
    // `getPreviousVersionCode() < MOVED_DEFAULTS_VERSION_CODE` with that
    // constant at 14, which is one-shot only for as long as someone
    // remembers to ship versionCode 14. The app is at 13, so the comparison
    // was true on EVERY launch and the prune was permanent rather than
    // one-shot -- and a permanent prune is this change's own defect
    // inverted. `Machine/settings.json` is what the Settings editor's
    // Remote tab writes to, so a user who set one of the sixteen pairs
    // there had it deleted again on the next launch, silently, for ever.
    // Bumping versionCode instead would have been worse: `isFirstRun` gates
    // on it too, so it costs every existing install a full re-extraction.
    //
    // Recorded only after the write below succeeds, so a run that dies
    // first prunes again on the next launch, which is the direction to fail
    // in. That is what the versionCode form got right and is kept.
    //
    // One case has a settings file on disk before any setup has ever been
    // recorded in SharedPreferences: an Android Auto Backup restore onto a
    // new device (backup_rules.xml includes only home/.vscodroid/data/Machine,
    // where this document lives, and nothing of shared_prefs/). When the restored document already carries
    // `vscodroid.layout.compactScreen`, it was written by a post-migration
    // build, so any pair in it matching [MOVED_DEFAULTS] was put there by
    // the user and must not be stripped.
    val document = refreshed ?: current
    val restoredPostMigration =
        !hasMovedDefaultsPrunedKey() &&
            storedSetupVersion().isNullOrEmpty() &&
            getPreviousVersionCode() == 0 &&
            COMPACT_SCREEN_KEY.containsMatchIn(commentsBlanked(document)) &&
            prunableMovedDefaults(document)
    if (restoredPostMigration) {
        markMovedDefaultsPruned()
    }
    val shouldPrune = !restoredPostMigration && !isMovedDefaultsPruned()
    // Whether it LOOKED, which is not the same as whether it changed anything.
    // A decline leaves the app's old preferences in the document, so recording
    // the pass as done for one would keep them there for ever.
    val looked = shouldPrune && prunableMovedDefaults(document)
    val pruned = if (shouldPrune) {
        pruneMovedDefaults(document, isCompactScreen())
    } else {
        null
    }
    val updated = settingsWithLayoutDefaults(pruned ?: refreshed ?: current, isCompactScreen())
        ?: pruned
        ?: refreshed
        ?: run {
            // Nothing to write at all, which for a run that pruned means the
            // document was already free of them. That is this migration's
            // definition of done, and not recording it here is what would
            // leave the pass running on every launch for ever.
            if (looked) markMovedDefaultsPruned()
            return
        }

    if (shouldPrune && !looked) {
        // Persist the pending prune before our layout backfill can resemble a restore.
        val recorded = recordPendingMovedDefaultsPrune()
        if (!recorded) {
            Logger.w(tag, "Could not record pending settings migration; settings.json is unchanged")
            return
        }
    }

    // Atomic because this file is the user's, not ours -- it carries their
    // editor preferences, and this runs at every launch. writeText truncates
    // first, so a failure between truncate and write leaves settings.json
    // empty or half-written, and the workbench reads that as "no settings"
    // rather than as an error. What we came to change is one path. Through
    // a link, for the same reason: a file kept elsewhere and linked here
    // would otherwise be replaced by a copy.
    if (!writeAtomically(settingsFile, throughLink = true) { it.write(updated.toByteArray()) }) {
        Logger.w(tag, "Could not refresh managed paths; settings.json is unchanged")
        return
    }
    if (looked) markMovedDefaultsPruned()
    Logger.i(tag, "Refreshed managed paths in settings.json")
}


/**
 * Moves settings.json from the path this app used to write to the one the
 * workbench reads.
 *
 * Everything written to the old path was inert (the theme, the terminal
 * profile, the Python interpreter, all of it), so the move is what makes those
 * defaults take effect for the first time. It is a move rather than a fresh
 * write because the old file is reachable from the terminal and may have been
 * edited by hand.
 *
 * Runs on every launch and does nothing once the file is in place. If both
 * exist the new one wins and the old is left alone, since only the new one has
 * been in use.
 */
internal fun FirstRunSetup.migrateSettingsToMachinePathImpl() {
    val legacy = legacySettingsFile()
    val current = File(Environment.getMachineSettingsPath(context))
    holdingWriteLock(current) {
        if (current.exists() || !legacy.exists()) return@holdingWriteLock

        current.parentFile?.mkdirs()
        // Through a temporary file, because the guard above turns any partial
        // arrival into a permanent one. A byte copy interrupted by a full disk
        // or by process death leaves what it managed to write under the
        // destination name; `exists()` accepts that on every later launch and
        // returns, so the truncated file becomes the user's settings for good
        // while the intact original sits beside it, never read and never
        // deleted. The workbench does not report a short settings.json as an
        // error either -- it reads it as the settings -- so the loss is silent.
        // Failing outright instead leaves the original in place and lets the
        // next launch, with room, finish the move.
        // Through a link at the destination, as every writer of this file goes: replaced, a link to a settings
        // file the person keeps elsewhere was lost.
        val copied = legacy.inputStream().use { source ->
            writeAtomically(current, throughLink = true) { source.copyTo(it) }
        }
        if (copied && legacy.delete()) {
            Logger.i(tag, "Moved settings.json to the path the workbench reads")
        } else {
            Logger.e(tag, "Could not move settings.json to ${current.absolutePath}")
        }
    }
}


/** Where this app wrote settings.json before the workbench's machine path; see [migrateSettingsToMachinePath]. */
internal fun FirstRunSetup.legacySettingsFile() = File(context.filesDir, "home/.vscodroid/User/settings.json")


internal fun FirstRunSetup.createDefaultSettingsImpl() {
    // Environment.getMachineSettingsPath explains why it is this path and not
    // the `User/` one that looks like the obvious home for user settings.
    val settingsFile = File(Environment.getMachineSettingsPath(context))
    holdingWriteLock(settingsFile) {
        if (!settingsFile.exists()) {
            // Thrown for the reason createBashrc() throws: this runs only from
            // runSetupLocked, markSetupComplete() is downstream in the same try
            // block, and the every-launch repair that would otherwise catch up
            // opens with `if (!settingsFile.exists()) return`. A swallowed
            // failure here means no terminal profile, no git.path, no
            // claudeProcessWrapper and no verifySignature until the app
            // updates -- reported to the user as a successful first run.
            if (!writeDefaultSettings()) {
                // Not for a link into a folder that has gone, for the reason createBashrc gives:
                // the launch repair writes through it once the folder is back (repairTruncatedSetupFiles).
                if (!Files.isSymbolicLink(settingsFile.toPath())) throw IOException("could not write $settingsFile")
                Logger.w(tag, LINKED_SETTINGS_UNWRITTEN)
                return@holdingWriteLock
            }
            // This document is ours and was written a line ago, so it holds none
            // of the preferences the prune exists to take out. Recording that here
            // is what stops a fresh install spending its one pass on the second
            // launch, over a document that by then can only differ from this one
            // by what the USER has written into it: the Settings editor's Remote
            // tab writes to this very file, and the pairs are matched value-exact,
            // so a first-day choice that happens to match an old default would be
            // deleted once and never again. An install that never carried them
            // should never look for them.
            markMovedDefaultsPruned()
        }
    }
}


/**
 * Turns `vscodroid.antigravity.runEngine` off in a machine settings file
 * that came with a backup or a move from another phone.
 *
 * `data/Machine` travels in both (data_extraction_rules.xml), and the
 * setting is the person's consent to an engine whose port every app on the
 * phone can reach, given for the apps on that phone: platform-fix.js starts
 * the engine whenever this file says so, and the process monitor's notice
 * that asks for it is shown only while it is off. So the answer stays
 * behind and the new phone asks again. Everything else in the file is
 * kept. Called only where no build has completed setup, so an install
 * keeps its own answer through every upgrade.
 *
 * Thrown for the reason [createDefaultSettings] throws: a failure here
 * would start the engine on the new phone with no notice at all.
 */
internal fun FirstRunSetup.withdrawRestoredEngineConsentImpl() {
    val settingsFile = File(Environment.getMachineSettingsPath(context))
    // From the read to the write under the lock [updateSettingsNativeLibPaths] holds, so a
    // refresh from a second launch cannot write back what it read before this.
    holdingWriteLock(settingsFile) {
        if (!settingsFile.exists()) return@holdingWriteLock
        val updated = withEngineConsentWithdrawn(settingsFile.readText()) ?: return@holdingWriteLock
        if (!writeAtomically(settingsFile, throughLink = true) { it.write(updated.toByteArray()) }) {
            throw IOException("could not write $settingsFile")
        }
        Logger.i(tag, "Turned the Antigravity engine off in settings restored from another phone")
    }
}


/**
 * Writes the managed settings this app owns, over whatever is at the path.
 *
 * Split from [createDefaultSettings]'s `!exists()` guard for the reason
 * [writeBashrc] is: [repairTruncatedSetupFiles] has to be able to replace an
 * empty settings.json without deleting it first to satisfy that guard, and
 * the delete is what turned a file this app could still heal on any later
 * launch into one nothing writes again. The rename leaves the empty file in
 * place if the write fails, so the repair keeps its next attempt.
 *
 * @return true if settings.json now holds these defaults; on false it is
 *   untouched, per [writeAtomically].
 */
internal fun FirstRunSetup.writeDefaultSettings(): Boolean {
    val nativeLibDir = context.applicationInfo.nativeLibraryDir
    // Environment.getMachineSettingsPath explains why it is this path and not
    // the `User/` one that looks like the obvious home for user settings.
    val settingsFile = File(Environment.getMachineSettingsPath(context))
    settingsFile.parentFile?.mkdirs()
    // The terminal profile is read, and the `linux` suffix is not a guess.
    // The workbench builds the key from the OS *integer* the remote sends:
    // `getPlatformKey()` maps 1 to "windows", 2 to "osx" and everything
    // else to "linux", and the server computes that integer as
    // `isMacintosh || isIOS ? 2 : isWindows ? 1 : 3`. Linux is the branch
    // nothing tests for, so Android (neither darwin nor win32) has always
    // landed on it. isLinux is not consulted anywhere on this path, so
    // patches/0001 neither made this work nor is needed for it.
    //
    // Both fields below carry weight: the path names the symlink so the
    // basename is `bash`, which is the key the ptyHost looks up to decide
    // the injection arguments, and the args stay empty because it only
    // injects shell integration for empty or login args.
    //
    // This block was once documented as inert, on the grounds that the
    // remote "reports its platform as android". No such mechanism exists:
    // it reports an integer, never a platform string. The device
    // measurement offered as proof predates the settings-path fix, when
    // everything written here went to a file the workbench never read, so
    // no default profile was selected and terminals took the $SHELL
    // fallback for an unrelated reason.
    // Atomic like the migration and the refresh, and for the same
    // reason: the guard is the file's own existence, so a first-run
    // write that stopped partway would leave a truncated settings.json
    // that `exists()` accepts forever. The workbench reads a short file
    // as the settings rather than as an error, so the user would come
    // up with an arbitrary subset of the defaults and no way to tell.
    //
    // The two Python discovery keys are pinned here as well as in
    // [refreshManagedPaths], and the duplication is load-bearing:
    // SplashActivity runs that refresh BEFORE runSetup(), so on a clean
    // install it returns at its own `!exists()` guard and a first
    // session would otherwise run unpinned. See PYTHON_LOCATOR for why
    // neither value is a preference.
    //
    // WHAT IS NOT HERE, and must not come back: the app's own preferences.
    // The theme, the word wrap, the minimap, the font sizes, the compact
    // activity bar, the hidden secondary side bar and the startup editor
    // were all written here and all had to move, because this file is not a
    // defaults file. The workbench parses it as the REMOTE USER settings and
    // merges it ON TOP of the user's own settings, so a preference written
    // here beats the one the user just changed in the Settings editor, which
    // writes to the local user file. The symptom was a Settings editor that
    // did nothing, with no error and no marker, on the two settings people
    // change first.
    //
    // They now ship as `contributes.configurationDefaults` in the bundled
    // welcome extension, which lands in the DEFAULT layer below every user
    // file, which is what a default is. What stays here is what belongs
    // here: machine facts (the shell, git, the Claude wrapper, the Python
    // interpreter), keys only the server reads, and the inert ones named
    // below. `vscodroid.layout.compactScreen` is the one addition and it is
    // a machine fact too: nothing in the extension API reports how wide the
    // window is, so the one process that knows writes it down, and the
    // extension reads it only when the user has expressed no preference.
    //
    // WHICH KEYS CAN LAND HERE AT ALL, because the answer is not "any of
    // them" and the file gives no sign when one cannot. Two readers open
    // this path and they filter differently:
    //
    //  - the server (out/server-main.js) builds its ConfigurationService on
    //    `machineSettingsResource` with an empty options object, so it reads
    //    every key whatever its declared scope. `extensions.verifySignature`
    //    reaches it that way: the server is what downloads a gallery
    //    extension and it is the only reader of that key.
    //  - the workbench parses the same file through RemoteUserConfiguration
    //    with REMOTE_MACHINE_SCOPES, which is every scope EXCEPT
    //    APPLICATION. An APPLICATION-scoped key is dropped before the
    //    workbench sees it.
    //
    // So a setting only the web client reads and that upstream registers
    // with `scope: 1` cannot be defaulted from this file, however plainly it
    // belongs beside its neighbours. `extensions.autoCheckUpdates` and
    // `extensions.autoUpdate` are exactly that shape: both scope 1, both
    // read only by the workbench's ExtensionsWorkbenchService, absent from
    // server-main.js entirely. Writing them here would look like turning off
    // the startup gallery query and the unattended updates and would turn
    // off neither, which is worse than leaving them alone, because the next
    // reader of this file would believe it.
    //
    // Three keys BELOW are already that shape, and they stay because removing
    // one would read as a change of behaviour when it is none.
    // `security.workspace.trust.enabled`: what settles trust is the
    // `--disable-workspace-trust` the server is started with. `update.mode`
    // and `update.showReleaseNotes`: both are registered `scope: 1` in the
    // shipped workbench bundle and appear nowhere in `out/server-main.js`, so
    // neither reader of this file can act on either. What actually keeps this
    // build off an update service is that the packaged `product.json` carries
    // no `updateUrl` for anything to ask.
    //
    // `terminal.integrated.env.linux`, the exec interceptor's row, is not in
    // this template: it is added below, through the same insert that reaches
    // an install made before the setting existed, and only once the library
    // is on disk.
    val defaults = """
        {
            "vscodroid.layout.compactScreen": ${isCompactScreen()},
            "terminal.integrated.defaultProfile.linux": "bash",
            "terminal.integrated.profiles.linux": {
                "bash": {
                    "path": "${Environment.getTerminalShellPath(context)}",
                    "args": [],
                    "icon": "terminal-bash"
                }
            },
            "git.path": "$nativeLibDir/libgit.so",
            "extensions.verifySignature": false,
            "telemetry.telemetryLevel": "off",
            "telemetry.enableTelemetry": false,
            "update.mode": "none",
            "update.showReleaseNotes": false,
            "security.workspace.trust.enabled": false,
            "python.languageServer": "Jedi",
            "python.defaultInterpreterPath": "${context.filesDir.absolutePath}/usr/bin/python3",
            "python.locator": "js",
            "python.useEnvironmentsExtension": false,
            "claudeCode.claudeProcessWrapper": "${Environment.getClaudeLauncherPath(context)}",
            "launch": {
                "version": "0.2.0",
                "configurations": [
                    {
                        "name": "Attach to Node.js",
                        "type": "node",
                        "request": "attach",
                        "port": 9229,
                        "restart": true,
                        "skipFiles": ["<node_internals>/**"]
                    },
                    {
                        "name": "NestJS: Debug",
                        "type": "node",
                        "request": "launch",
                        "runtimeArgs": ["--inspect", "-r", "ts-node/register", "-r", "tsconfig-paths/register"],
                        "args": ["${'$'}{workspaceFolder}/src/main.ts"],
                        "skipFiles": ["<node_internals>/**"],
                        "console": "integratedTerminal"
                    },
                    {
                        "name": "Node.js: Run Current File",
                        "type": "node",
                        "request": "launch",
                        "program": "${'$'}{file}",
                        "skipFiles": ["<node_internals>/**"],
                        "console": "integratedTerminal"
                    }
                ]
            }
        }
    """.trimIndent()
    // `terminal.integrated.env.linux` carries the exec interceptor, and it
    // is that setting and not the bash profile's own `env` for two reasons
    // measured on API 33 and 36 emulators, 2026-09-22/23. A profile env is
    // invisible for the whole first session after the file gained the line
    // while the app was stopped (the workbench compares profiles by name,
    // args, path and the like and never by env, which is the likely cause
    // and not a measured one); and it never reaches a `"type": "process"`
    // task, which takes no profile at all. env.linux is read by the server
    // at every process creation, so it reaches terminals, shell tasks and
    // process tasks alike and applies live. Nothing an extension spawns
    // without a pty sees either, which is the boundary of terminal scope.
    // `"LD_PRELOAD": null` in this object is the off switch, and the refresh
    // leaves a key of any value alone but one: this app's own path under
    // another Android user's data directory, which it re-points.
    //
    // Only once the library is on disk, the rule [updateSettingsNativeLibPaths]
    // follows: a preload the linker cannot find aborts every exec in that
    // environment, the shell included, so a build whose assets lack the file
    // (a stale CI cache, a skipped `scripts/build-termux-exec.sh`) must
    // write no line here, or a fresh install has no working terminal at
    // all. A fresh install writes this after `usr/` is extracted, so it gets
    // the file and the line together; an install the file reaches later
    // gets the line from the refresh on the launch after. One writer of the
    // line, [ensureTerminalPreload], so the two paths cannot disagree on
    // its shape.
    val preloadPath = Environment.getExecPreloadPath(context)
    val content = if (File(preloadPath).isFile) {
        ensureTerminalPreload(defaults, preloadPath, context.packageName) ?: defaults
    } else {
        defaults
    }
    return writeAtomically(settingsFile, throughLink = true) { it.write(content.toByteArray()) }
}


/**
 * The bundled bash inside the terminal profile, and the bundled git. Both are
 * anchored on their key and match only a nativeLibraryDir value, so a path the
 * user chose themselves is left alone.
 *
 * The character class excludes braces deliberately: an earlier release used
 * `/data/app/[^"]+/lib/[^"]+`, whose tail ran straight past the directory and
 * swallowed the binary filename, leaving the terminal profile pointing at a
 * directory (issue #3). Every quantifier here is fenced by the delimiter it
 * must not cross.
 */
private val LEGACY_BASH_PROFILE_PATH = Regex(
    """("terminal\.integrated\.profiles\.linux"\s*:\s*\{\s*"bash"\s*:\s*\{[^{}]*?"path"\s*:\s*)"/data/app/[^"]*["]"""
)

private val GIT_PATH = Regex("""("git\.path"\s*:\s*)"/data/app/[^"]*["]""")


/**
 * The two settings that shipped alongside the old profile path and blocked shell
 * integration with it. Matched only in the exact shape this app wrote, so a
 * profile the user has since edited is left as they wrote it.
 */
private val LEGACY_PROFILE_ARGS = Regex(
    """("terminal\.integrated\.profiles\.linux"\s*:\s*\{\s*"bash"\s*:\s*\{[^{}]*?"args"\s*:\s*)\["-i"\]"""
)

private val SHELL_INTEGRATION_OFF = Regex(
    """("terminal\.integrated\.shellIntegration\.enabled"\s*:\s*)false"""
)


/**
 * The Claude Code wrapper path, under the same rule as the two paths above: it
 * matches only a value this app wrote, so a wrapper the user pointed somewhere
 * themselves is theirs to keep. Without an anchor this rewrote whatever it
 * found on every launch, which the doc on those two says is exactly what none
 * of them should do.
 *
 * Two managed shapes, not one. The current value lives in nativeLibraryDir and
 * moves on every reinstall. The other is `filesDir/usr/bin/...`, which is what
 * releases before the CLI stopped being bundled wrote there -- still ours to
 * re-point, and the reason an anchor on `/data/app/` alone would have stranded
 * those installs.
 *
 * [CLAUDE_WRAPPER_KEY] exists because refreshing and inserting are different
 * decisions once the anchor is there. A user-chosen value no longer matches
 * [CLAUDE_WRAPPER], and treating "did not match" as "not present" would write a
 * second copy of the key beside theirs.
 */
private val CLAUDE_WRAPPER = Regex(
    """("claudeCode\.claudeProcessWrapper"\s*:\s*)"(?:/data/app/|/data/(?:user/0|data)/[^"]*/files/usr/bin/)[^"]*""""
)

private val CLAUDE_WRAPPER_KEY = Regex(""""claudeCode\.claudeProcessWrapper"\s*:""")


/**
 * Whether the user's settings already mention extension signature verification.
 *
 * Only its presence matters, either value: someone who turned it back on meant to.
 */
private val VERIFY_SIGNATURE = Regex(""""extensions\.verifySignature"\s*:""")


/**
 * The two settings that route Python environment discovery through `pet`, the
 * Python extension's native locator.
 *
 * Pinned rather than defaulted, and not as a preference: `pet` is a Rust binary
 * the extension spawns from `<extension>/python-env-tools/bin/pet`, and it is
 * not in the artefact this app can obtain. Open VSX serves `ms-python.python`
 * only as a `universal` VSIX, packaged without a target, and that recipe never
 * compiles the locator. Measured on the bundled 2026.4.0 tree, which is also
 * the newest the registry serves: no `python-env-tools` directory, and no ELF
 * file of any kind. Nothing chosen at download time can change that.
 *
 * With `python.locator` on `native` the extension therefore spawns a path that
 * does not exist, warns "Python Locator failed to start" with a Do Not Show
 * Again button, and lists no interpreters (issue #241). Silencing that button
 * is what must not be mistaken for a fix.
 *
 * `python.useEnvironmentsExtension` is the second key because the extension
 * tests it FIRST, in `initialize`, and a true value hands discovery to
 * `ms-python.vscode-python-envs` before the locator gate is ever read. That
 * extension is installable from Open VSX (it is in this one's extension pack)
 * and walks into the same wall: its 1.36.0 universal VSIX names
 * `python-env-tools/bin/pet` in its bundle, ships no such file, and falls back
 * to looking for it inside the Python extension. Pinning only the locator would
 * leave that branch reaching the same missing binary.
 *
 * Both default to the safe value already, so what this covers is a document
 * where something else has moved them: both carry the `onExP` tag, so the value
 * in force is not only the user's to set. A hand edit back is undone at the
 * next launch, and that is the intent rather than a side effect, since there is
 * nothing on the device for either path to spawn.
 *
 * Shipping a per-platform VSIX would not lift this either. The extension tree
 * lives under `filesDir`, which SELinux refuses to `execve`, the same wall that
 * makes the Claude Code CLI run through musl's loader.
 */
private val PYTHON_LOCATOR = SettingPin("python.locator", """"[^"]*"""", "\"js\"")

private val PYTHON_ENV_EXTENSION =
    SettingPin("python.useEnvironmentsExtension", """(?:true|false)""", "false")


/**
 * A setting this app holds at [value] whatever the document says.
 *
 * [valuePattern] is the value shape that can be rewritten in place. A key
 * carrying anything else is left exactly as it stands rather than gaining a
 * second copy of itself further up, which is the distinction
 * [CLAUDE_WRAPPER_KEY] draws for the same reason.
 *
 * Held rather than merely inserted when absent, which is where this parts
 * company with [VERIFY_SIGNATURE]. Signature verification back on is a working
 * configuration and so a decision worth keeping; a locator pointed at a binary
 * that is not in the artefact has no working configuration to keep.
 */
private class SettingPin(val key: String, valuePattern: String, val value: String) {
    private val quotedKey = "\"" + key.replace(".", "\\.") + "\""
    private val managed = Regex("""($quotedKey\s*:\s*)$valuePattern""")
    private val present = Regex("""$quotedKey\s*:""")

    fun applyTo(content: String): String {
        val masked = commentsBlanked(content)
        return when {
            managed.containsMatchIn(masked) -> replaceSettingValues(content, managed, value)
            present.containsMatchIn(masked) -> content
            else -> insertSetting(content, key, value)
        }
    }
}


/** Match without comments, then replace only the value in the original text. */
private fun replaceSettingValues(content: String, pattern: Regex, value: String): String {
    val updated = StringBuilder(content)
    for (match in pattern.findAll(commentsBlanked(content)).toList().asReversed()) {
        val valueStart = match.range.first + match.groupValues[1].length
        updated.replace(valueStart, match.range.last + 1, value)
    }
    return updated.toString()
}


/**
 * The indentation of the root object's own first property, or null.
 *
 * Matched AT the opening brace rather than searched for from it, and the
 * difference is the whole point. The previous form was
 * `Regex("""(?<=\{)\s*\n([ \t]*)(?=")""")` used with `find(content, brace)`,
 * where the start index only says where scanning begins and the lookbehind
 * accepts ANY opening brace. settings.json is JSONC, so a comment is legal as
 * the first thing inside the root object, and one comment there slides the match
 * past it to the first NESTED object and reports that object's indentation as
 * the document's own.
 *
 * That is harmless where the answer only decides where a new line is placed, and
 * it is not harmless in [pruneMovedDefaults], which deletes a line by matching
 * key, value, indent and comma: at the wrong indent it removes the user's nested
 * overrides while leaving the app's root-level ones behind, which is the exact
 * inversion of what it is for. Anchoring here fixes both callers at once.
 *
 * No match on a root object that opens with a comment is the intended answer,
 * not a gap: the prune then declines, and [insertSetting] falls back to four
 * spaces, which is the conservative direction both already ask for.
 */
private val FIRST_PROPERTY = Regex("""\s*\n([ \t]*)(?=")""")


/**
 * The indent [FIRST_PROPERTY] answers for a document whose root brace is at
 * [brace], or null when the root object does not open with a plain property.
 */
private fun firstPropertyIndent(content: String, brace: Int): String? =
    FIRST_PROPERTY.matchAt(content, brace + 1)?.groupValues?.get(1)


/**
 * Reconciles the settings.json values this app manages, returning the updated
 * document or `null` when nothing needed changing.
 *
 * Two of the jobs are about paths. `git.path` still embeds `nativeLibraryDir`,
 * which a reinstall moves, so it is re-pointed whenever it has gone stale. The
 * terminal profile is instead migrated *off* it and onto `usr/bin/bash`, which
 * `setupToolSymlinks()` already repairs on every launch, and after that move the
 * pattern no longer matches and the profile never goes stale again.
 *
 * The move carries the other two halves of the shell-integration fix with it,
 * because all three were written by the same release. Bundling them keeps the
 * migration one-shot: once the path is off `/data/app/`, nothing here fires
 * again, so a user who later turns shell integration back off keeps it off.
 *
 * The remaining values are not migrations. They are inserted when absent rather
 * than only refreshed, so they reach installs made before the setting existed:
 * `claudeCode.claudeProcessWrapper`, itself a `nativeLibraryDir` path and so
 * refreshed too, but left alone when it points somewhere the user chose;
 * `extensions.verifySignature`; and the two Python pins [PYTHON_LOCATOR] and
 * [PYTHON_ENV_EXTENSION].
 *
 * Substitutes values in place and leaves every other byte untouched.
 * settings.json is JSONC: comments and trailing commas are legal there, so
 * parsing the document to re-serialise it would strip the user's comments,
 * escape every slash, and turn `["-i",]` into `["-i", null]`.
 *
 * A pattern that does not match changes nothing, so a file the user has
 * restructured is left as they wrote it rather than mangled.
 */
internal fun refreshManagedPaths(
    content: String,
    shellPath: String,
    gitPath: String,
    claudeWrapper: String,
): String? {
    var updated = replaceSettingValues(content, GIT_PATH, "\"$gitPath\"")

    val movedProfile =
        replaceSettingValues(updated, LEGACY_BASH_PROFILE_PATH, "\"$shellPath\"")
    if (movedProfile != updated) {
        updated = replaceSettingValues(movedProfile, LEGACY_PROFILE_ARGS, "[]")
        updated = replaceSettingValues(updated, SHELL_INTEGRATION_OFF, "true")
    }

    // Without this setting the Claude Code extension refuses to start at all:
    // resolveClaudeBinary() throws "Unsupported platform" rather than looking on
    // PATH. So an install that predates the setting needs it added, not just
    // refreshed. The value names musl's loader in nativeLibraryDir, so like the
    // two paths above it moves on every reinstall and has to be rewritten here.
    updated = when {
        // A managed value: refresh it, since nativeLibraryDir moves.
        CLAUDE_WRAPPER.containsMatchIn(commentsBlanked(updated)) ->
            replaceSettingValues(updated, CLAUDE_WRAPPER, "\"$claudeWrapper\"")
        // Present, but pointing somewhere the user chose. Leave it, and do not
        // insert beside it.
        CLAUDE_WRAPPER_KEY.containsMatchIn(commentsBlanked(updated)) -> updated
        else -> insertSetting(updated, "claudeCode.claudeProcessWrapper", "\"$claudeWrapper\"")
    }

    // Signature verification cannot run here and refuses the install when it
    // cannot: it loads @vscode/vsce-sign, which is not in this server's
    // node_modules. Left on, every marketplace install stops at "cannot verify
    // the extension signature / Signature verification was not executed" and
    // offers to proceed unverified, which teaches people to click past a
    // security prompt for no gain.
    //
    // Added for installs that predate it rather than only written at first run,
    // and skipped when the key is already present in either state, because
    // switching it back on is a decision worth keeping.
    if (!VERIFY_SIGNATURE.containsMatchIn(commentsBlanked(updated))) {
        updated = insertSetting(updated, "extensions.verifySignature", "false")
    }

    // The secondary side bar and the sash size were backfilled here. Both are
    // preferences, both now ship as contributed defaults in the bundled welcome
    // extension, and backfilling either would put the override straight back
    // into the file [pruneMovedDefaults] just took it out of.
    //
    // Reaches installs that already have a settings.json, which is every device
    // the failure was reported from: createDefaultSettings() writes only when
    // the file is absent, so on its own it would fix nobody who already has one.
    updated = PYTHON_LOCATOR.applyTo(updated)
    updated = PYTHON_ENV_EXTENSION.applyTo(updated)

    return updated.takeIf { it != content }
}


/**
 * Holds the one layout fact the editor cannot work out for itself.
 *
 * `vscodroid.layout.compactScreen` is not a preference and is not offered as
 * one: it says whether the editor has to share a phone-width window. Nothing in
 * the extension API reports how wide the window is, so the one process that
 * knows writes it down, and the bundled welcome extension reads it only where
 * the user has expressed no preference of their own.
 *
 * Why it matters at all, measured on a 411dp screen: 48px of activity bar and a
 * side bar that will not go below 170px however far its divider is dragged left
 * the editor 193dp, with the welcome text wrapping at three words a line. The
 * two answers to that are upstream's own compact activity bar and closing the
 * side bar when a file is opened, and both are now contributed defaults rather
 * than values written over the user.
 *
 * Held rather than inserted-when-absent, which is the opposite of the keys
 * beside it in [refreshManagedPaths] and is right for the same reason those are
 * not: a device fact that has gone stale is worse than absent, and there is no
 * user decision here to keep. The user's decision lives in
 * `vscodroid.layout.autoHideSideBar`, in their own settings file, where this
 * function never reaches.
 */
internal val COMPACT_SCREEN_KEY = Regex(""""vscodroid\.layout\.compactScreen"\s*:""")


internal fun settingsWithLayoutDefaults(content: String, compactScreen: Boolean): String? =
    SettingPin("vscodroid.layout.compactScreen", """(?:true|false)""", compactScreen.toString())
        .applyTo(content)
        .takeIf { it != content }


/**
 * The preferences this app used to write into the machine settings file.
 *
 * Each pair is a key and the exact raw JSON the app wrote beside it. They moved
 * to `contributes.configurationDefaults` in the bundled welcome extension, and
 * moving them there is only half the fix: `createDefaultSettings` writes the
 * document once and never again, so every device that already has one would
 * keep the override for ever, and the override is what beats the user's own
 * setting.
 *
 * `vscodroid.layout.autoHideSideBar` is not here because its value is the
 * device's; [pruneMovedDefaults] takes it as an argument instead.
 *
 * Drawn from EVERY shipped release's writer, not from the most recent one. The
 * document on disk is whatever the release the user first installed wrote, plus
 * whatever later releases inserted into it; a key one release stopped writing
 * is still in the file of everyone who was there for it.
 */
private val MOVED_DEFAULTS = listOf(
    "workbench.secondarySideBar.defaultVisibility" to "\"hidden\"",
    "workbench.sash.size" to "20",
    "workbench.activityBar.compact" to "true",
    "workbench.startupEditor" to "\"none\"",
    "workbench.colorTheme" to "\"Default Dark Modern\"",
    "editor.fontSize" to "14",
    "editor.wordWrap" to "\"on\"",
    "editor.minimap.enabled" to "false",
    "diffEditor.wordWrap" to "\"on\"",
    "terminal.integrated.fontSize" to "13",
    "terminal.integrated.shellIntegration.enabled" to "true",
    // v1.0.0 wrote these six into `home/.vscodroid/User/settings.json`, and
    // `migrateSettingsToMachinePath` moved that whole document, comments and
    // all, into the machine file on the upgrade to v1.1.0. v1.1.0 stopped
    // writing them and nothing has removed them since, so an install that began
    // at v1.0.0 still carries app-written GitLens preferences in the layer that
    // outranks the user's own. GitLens is no longer bundled, so the user who
    // installs it from Open VSX and turns code lens back on is overridden by a
    // release they last ran years of updates ago.
    "gitlens.showWelcomeOnInstall" to "false",
    "gitlens.showWhatsNewAfterUpgrades" to "false",
    "gitlens.codeLens.enabled" to "false",
    "gitlens.currentLine.enabled" to "true",
    "gitlens.hovers.enabled" to "false",
    "gitlens.statusBar.enabled" to "false",
)


/**
 * Takes the moved preferences back out of a document this app wrote them into.
 *
 * The only destructive edit this app makes to a file the user also owns, so it
 * is deliberately unable to touch anything it did not write. A line is removed
 * only at the root when all four of these hold: the key is one of [MOVED_DEFAULTS], the
 * value is byte-for-byte the one this app wrote, the indentation is the
 * document's own first-property indent, and the line ends in a comma. A value
 * the user changed is theirs and stays, and it goes on outranking their local
 * settings, which is the honest outcome: they set it, in a file the app hands
 * them, and silently deleting it would be the same defect pointed the other way.
 * Structural depth keeps nested overrides regardless of their indentation.
 *
 * Returns null when nothing matched, so a document that never had these keys
 * costs no write.
 */
internal fun pruneMovedDefaults(content: String, autoHideSideBar: Boolean): String? {
    // Declines rather than guesses when the document is not a shape this app
    // wrote, for the reason [insertSetting] gives at greater length: leaving a
    // default in place costs one override, and editing a file this app does not
    // understand costs the file, because the write that follows is atomic.
    val brace = rootBraceIndex(content)
    if (brace < 0) return null
    val indent = firstPropertyIndent(content, brace) ?: return null
    // Past here the document has been read and the answer is trustworthy: either
    // lines come out or there were none to take. Everything above is a decline,
    // which [prunableMovedDefaults] answers separately so the caller cannot record
    // a refusal as a success.
    // Matched against a copy with the comments blanked, and deleted from the
    // original by the offsets that copy reports. A commented-out setting starts
    // its line exactly as a live one does, so a plain text replace takes the
    // user's parked experiment out of their own comment and rewrites a file that
    // needed no write. `ProcessManager.heapOverrideFromSettings` learned the same
    // lesson reading a value; this is the writing half of it, and the blanks are
    // length-preserving so the two texts share one index space.
    val masked = commentsBlanked(content)
    val rootStrings = mutableSetOf<Int>()
    var depth = 0
    var cursor = brace
    while (cursor < masked.length) {
        when (masked[cursor]) {
            '"' -> {
                if (depth == 1) rootStrings.add(cursor)
                cursor = jsonStringEnd(masked, cursor).let { if (it < 0) masked.length else it }
            }
            '{', '[' -> depth++
            '}', ']' -> if (--depth == 0) break
        }
        cursor++
    }
    val moved = MOVED_DEFAULTS +
        ("vscodroid.layout.autoHideSideBar" to autoHideSideBar.toString())
    val cuts = mutableListOf<IntRange>()
    for ((key, value) in moved) {
        val needle = "\n$indent\"$key\": $value,"
        var at = masked.indexOf(needle)
        while (at >= 0) {
            if (at + 1 + indent.length in rootStrings) cuts.add(at until at + needle.length)
            at = masked.indexOf(needle, at + needle.length)
        }
    }
    if (cuts.isEmpty()) return null
    val updated = StringBuilder(content)
    for (cut in cuts.sortedByDescending { it.first }) {
        updated.delete(cut.first, cut.last + 1)
    }
    return updated.toString().takeIf { it != content }
}


/**
 * Whether [pruneMovedDefaults] can read this document at all.
 *
 * The same two preconditions it starts with, asked separately because its own
 * answer cannot carry the difference: it returns null both for a document that
 * holds none of the moved pairs and for one it declined to read, and those are
 * opposite facts. A decline means the app's old preferences may still be in
 * there; recording the pass as done for one would leave them for ever, which is
 * exactly what the one-shot flag must not do.
 *
 * The declining case is real rather than theoretical: [firstPropertyIndent]
 * answers null when the root object opens with a comment, and it was deliberately
 * made to answer that way so a leading comment could not send the prune into a
 * nested object instead. settings.json is JSONC and belongs to the user, and the
 * editor tells them to edit it by hand.
 */
internal fun prunableMovedDefaults(content: String): Boolean {
    val brace = rootBraceIndex(content)
    return brace >= 0 && firstPropertyIndent(content, brace) != null
}


/**
 * [content] with every comment replaced by spaces, and nothing else moved.
 *
 * Same length, and the newlines are kept, so an offset found here is the same
 * offset in the original. Strings are left as they are: the arm that recognises
 * them is what stops a delimiter inside a value opening a comment that runs to
 * the end of the file.
 *
 * A string is walked whole before a comment is looked for, and a `//` or a
 * block opener that has no end is left as text. `ProcessManager`'s reader of the
 * heap ceiling is the other caller and documents at length why a delimiter has to
 * be read in order with the strings around it.
 *
 * A loop and not a pattern: the pattern this replaced, an alternation under a
 * star, ran out of stack on a long string, in a file the user makes as long as
 * they like and that every server start reads.
 */
internal fun commentsBlanked(content: String): String {
    val out = StringBuilder(content.length)
    var i = 0
    while (i < content.length) {
        val c = content[i]
        var end = -1
        if (c == '"') {
            // A string that never closes is no string: its quote is an ordinary character and the
            // text after it is read as text, which is what the pattern this replaced did.
            val close = jsonStringEnd(content, i)
            if (close >= 0) {
                out.append(content, i, close + 1)
                i = close + 1
                continue
            }
        } else if (content.startsWith("//", i)) {
            // To the first CR or LF, as VS Code's scanner and platform-fix.js end it: a file whose
            // lines end in a bare CR has its next line read as a comment otherwise, and a setting on
            // that line is neither seen here nor turned off.
            end = content.indexOfAny(charArrayOf('\r', '\n'), i).let { if (it < 0) content.length else it }
        } else if (content.startsWith("/*", i)) {
            end = content.indexOf("*/", i + 2).let { if (it < 0) -1 else it + 2 }
        }
        if (end < 0) {
            out.append(c)
            i++
        } else {
            for (k in i until end) out.append(if (content[k] == '\n') '\n' else ' ')
            i = end
        }
    }
    return out.toString()
}


/**
 * The offset of the quote that closes the string literal opening at [start], or -1 when it never
 * does. A plain loop: the pattern this replaced, an alternation under a star, ran out of stack on a
 * long string, and a settings file is the user's to make as long as they like.
 */
private fun jsonStringEnd(text: String, start: Int): Int {
    var i = start + 1
    while (i < text.length) {
        when (text[i]) {
            '\\' -> i++
            '"' -> return i
        }
        i++
    }
    return -1
}


/**
 * [content] with every `true` that can give `vscodroid.antigravity.runEngine` its value made `false`,
 * or null when there is none to change.
 *
 * The three spellings platform-fix.js reads as the setting, which is how VS Code builds its settings
 * from a file: the root key `vscodroid.antigravity.runEngine`, the key `runEngine` of an object under
 * the root key `vscodroid.antigravity`, and `runEngine` under `antigravity` under `vscodroid`. A key may
 * be spelled with escapes. One in a comment, in another object, a language block included, or inside
 * a string is not the setting. Every copy is turned off and not only the last, which can only turn
 * off a `true` that a later key already replaced: the answer a restore leaves behind is then "off"
 * whichever of them the reader keeps. Only the value is replaced, so the rest of the person's file
 * stays as it was. A document this cannot read is left alone: platform-fix.js cannot read it either,
 * and reads it as off.
 *
 * One pass with a stack of the objects and arrays being read, and no pattern over the text, so a
 * string of any length or a deep nesting costs time and never an `Error` out of setup.
 */
internal fun withEngineConsentWithdrawn(content: String): String? {
    val brace = rootBraceIndex(content).takeIf { it >= 0 } ?: return null
    val masked = commentsBlanked(content)
    val values = mutableListOf<Int>()
    val keys = ArrayList<String?>() // the key each open container sits under, null for the root
    val objects = ArrayList<Boolean>() // whether each open container is an object
    var key: String? = null // the decoded key whose value comes next, null when unknown
    var wantKey = false
    var i = brace
    while (i < masked.length) {
        val c = masked[i]
        when {
            c == '{' || c == '[' -> {
                keys.add(key)
                objects.add(c == '{')
                key = null
                wantKey = c == '{'
            }
            c == '}' || c == ']' -> {
                if (keys.isEmpty()) return null
                keys.removeAt(keys.lastIndex)
                objects.removeAt(objects.lastIndex)
                key = null
                if (keys.isEmpty()) break
                wantKey = false
            }
            c == ',' -> {
                key = null
                wantKey = objects.last()
            }
            c == '"' -> {
                val close = jsonStringEnd(masked, i)
                if (close < 0) return null
                key = if (wantKey) decodedKey(masked, i, close) else null
                wantKey = false
                i = close
            }
            c == ':' || c.isWhitespace() -> Unit
            else -> {
                // A number, `true`, `false` or `null`: a value, which ends whatever key it belonged to.
                var end = i + 1
                while (end < masked.length && (masked[end].isLetterOrDigit() || masked[end] in "+-._")) end++
                if (end - i == 4 && masked.startsWith("true", i) && namesEngineConsent(keys, key)) values.add(i)
                key = null
                i = end - 1
            }
        }
        i++
    }
    if (values.isEmpty()) return null
    val updated = StringBuilder(content)
    for (at in values.asReversed()) updated.replace(at, at + 4, "false")
    return updated.toString()
}


/** The string literal at [open]..[close] with its escapes read, or null for one too long to be the key. */
private fun decodedKey(text: String, open: Int, close: Int): String? =
    if (close - open > ENGINE_CONSENT_KEY_LIMIT) {
        null
    } else {
        runCatching { org.json.JSONTokener(text.substring(open, close + 1)).nextValue() as? String }.getOrNull()
    }


/**
 * Whether a `true` under [key], inside the containers whose own keys are [keys] (the root's first, and
 * null), is the setting. A container in an array, or under a key too long or too broken to read, has a
 * null key, which no spelling of the setting has, so a path through either fails here.
 */
private fun namesEngineConsent(keys: List<String?>, key: String?): Boolean =
    key != null && (keys.drop(1) + key) in ENGINE_CONSENT_PATHS


private val ENGINE_CONSENT_PATHS = listOf(
    listOf("vscodroid.antigravity.runEngine"),
    listOf("vscodroid.antigravity", "runEngine"),
    listOf("vscodroid", "antigravity", "runEngine"),
)


/** The longest literal, escapes counted, that can spell one of the names above: six characters a letter. */
private const val ENGINE_CONSENT_KEY_LIMIT = 256


/**
 * Adds a setting to a JSONC document, directly after its opening brace.
 *
 * The document belongs to the user and is full of comments and formatting this
 * app has no business reflowing, so exactly one line is inserted and nothing
 * else moves. It borrows the indentation of the first property when there is
 * one, which covers the document this app writes and anything formatted like it.
 *
 * [value] is written as-is, so it is raw JSON: callers quote their own strings.
 * That is what lets a boolean through -- "false" and "\"false\"" are different
 * settings, and the second one is not what any of these keys accept.
 */
private fun insertSetting(content: String, key: String, value: String): String {
    val brace = rootBraceIndex(content)
    if (brace < 0) return content
    val indent = firstPropertyIndent(content, brace) ?: "    "
    return content.substring(0, brace + 1) +
        "\n$indent\"$key\": $value," +
        content.substring(brace + 1)
}


/**
 * The terminal environment object, anchored on the whole key so the `osx` and
 * `windows` objects that may sit beside it are never the one edited. The first
 * form finds the key with any value, the second only an object.
 */
private val TERMINAL_ENV_LINUX_KEY = Regex(""""terminal\.integrated\.env\.linux"\s*:""")

private val TERMINAL_ENV_LINUX_OPEN = Regex(""""terminal\.integrated\.env\.linux"\s*:\s*\{""")

private val LD_PRELOAD_KEY = Regex(""""LD_PRELOAD"\s*:""")


/**
 * An `LD_PRELOAD` entry that is the whole of this app's own interceptor path
 * under any Android user's data directory, or under `/data/data`, the path
 * captured. Anchored on the closing quote, so a list naming it beside another
 * library does not match.
 */
private fun ownPreload(packageName: String): Regex {
    val tail = Regex.escape("$packageName/files/${Environment.EXEC_PRELOAD_ASSET}")
    return Regex(""""LD_PRELOAD"\s*:\s*"(/data/(?:user/\d+|data)/$tail)"""")
}


/**
 * Puts the exec interceptor into `terminal.integrated.env.linux`, or declines.
 *
 * Reaches installs made before the setting existed, the way [insertSetting]'s
 * callers in [refreshManagedPaths] do: the key is inserted when absent, one
 * line is added inside an object that holds no `"LD_PRELOAD"`, and a key that
 * is already there is left exactly as it stands. That last rule is the whole
 * of the user's control over the feature. A path of their own is a choice, and
 * `"LD_PRELOAD": null` is the off switch, since a null in this object deletes
 * the variable from every terminal; both are theirs and neither is touched,
 * the rule [CLAUDE_WRAPPER_KEY] draws. A key holding something other than an
 * object is not a shape this app wrote, and is left alone rather than guessed
 * at.
 *
 * One value is this app's to correct: its own library named under another
 * Android user's data directory, or under `/data/data`, which is what a backup
 * of this file restored into another user carries. That path cannot be opened
 * here, and Bionic aborts every exec whose preload it cannot load, so left
 * alone it kills every terminal and task. It is re-pointed to [preloadPath],
 * the way [CLAUDE_WRAPPER] re-points the paths this app wrote; a value that
 * lists it beside another library is the user's, and stays.
 *
 * The object is scanned with comments blanked and strings stepped over, so a
 * `}` inside a value cannot end it early and a mention inside a comment cannot
 * count as the key. Either mistake writes a second copy of the key beside the
 * user's, which the workbench then resolves in an order this app does not
 * control. The indent is borrowed from the object's first property, as the
 * root insert borrows the document's; an object opening with a comment or
 * holding nothing takes one level past the key's own line instead. The flat
 * four-space fallback the root insert uses put the line flush with the key
 * here, which is where an emptied object sits after the off switch is deleted
 * through the Settings editor and the next launch refills it: valid JSON, and
 * the wrong shape for a file the user reads. Measured on API 33 and 36
 * emulators, 2026-09-23.
 *
 * @return the document with the line added or re-pointed, or null when there
 *   is nothing to write.
 */
internal fun ensureTerminalPreload(content: String, preloadPath: String, packageName: String): String? {
    val scan = commentsBlanked(content)
    val open = TERMINAL_ENV_LINUX_OPEN.find(scan)
        ?: return if (TERMINAL_ENV_LINUX_KEY.containsMatchIn(scan)) {
            null
        } else {
            insertSetting(content, "terminal.integrated.env.linux", "{ \"LD_PRELOAD\": \"$preloadPath\" }")
                .takeIf { it != content }
        }
    val brace = open.range.last
    val close = objectEnd(scan, brace)
    if (close < 0) return null
    if (LD_PRELOAD_KEY.containsMatchIn(scan.substring(brace, close))) {
        val value = ownPreload(packageName).find(scan.substring(0, close), brace)?.groups?.get(1)?.range
            ?: return null
        return if (content.substring(value) == preloadPath) null else content.replaceRange(value, preloadPath)
    }
    val indent = firstPropertyIndent(content, brace) ?: nestedIndent(content, open.range.first)
    val comma = if (scan.substring(brace + 1, close).isBlank()) "" else ","
    return content.substring(0, brace + 1) +
        "\n$indent\"LD_PRELOAD\": \"$preloadPath\"$comma" +
        content.substring(brace + 1)
}


/**
 * The indent one level past the line holding [key], for a line added inside
 * an object that has no property of its own to borrow from: the key's own
 * indent plus the step the document's root properties use, or four spaces
 * when the root opens with a comment and offers none.
 */
private fun nestedIndent(content: String, key: Int): String {
    val own = content.substring(content.lastIndexOf('\n', key) + 1, key).takeWhile { it == ' ' || it == '\t' }
    val root = rootBraceIndex(content)
    val step = (if (root >= 0) firstPropertyIndent(content, root) else null) ?: "    "
    return own + step
}


/**
 * The offset of the `}` closing the object whose `{` is at [open], or -1 when
 * it never closes. Strings are stepped over; comments are the caller's to blank.
 */
private fun objectEnd(scan: String, open: Int): Int {
    var depth = 0
    var i = open
    while (i < scan.length) {
        when (scan[i]) {
            '"' -> i = jsonStringEnd(scan, i).also { if (it < 0) return -1 }
            '{' -> depth++
            '}' -> if (--depth == 0) return i
        }
        i++
    }
    return -1
}


/**
 * The offset of the document's own opening brace, or -1 when there is not one.
 *
 * `indexOf('{')` answers a different question. This is JSONC, so comments may
 * precede the root object, and a brace inside one is indistinguishable to a plain
 * search: the line then lands in the middle of that comment. The cost is not the
 * managed setting being missed. A document that no longer parses drops EVERY
 * setting it holds, the user's theme and font size included, and the write is
 * atomic, so the file it replaces is already gone.
 *
 * Only whitespace and comments are legal ahead of the root brace, so skipping
 * exactly those two is the whole scan. Anything else means this is not a shape
 * this app wrote or understands, and -1 leaves it untouched rather than guessing:
 * declining to add a setting costs one default, and guessing wrong costs the file.
 * A byte order mark at the very start is stepped over too, as platform-fix.js
 * reads the file: an editor that saves with one left a settings file that got
 * none of the settings this app adds, the Claude launcher among them.
 */
private fun rootBraceIndex(content: String): Int {
    var i = if (content.startsWith('\uFEFF')) 1 else 0
    while (i < content.length) {
        val c = content[i]
        when {
            c == '{' -> return i
            c.isWhitespace() -> i++
            content.startsWith("//", i) -> {
                // To the first CR or LF, as [commentsBlanked] ends it.
                val newline = content.indexOfAny(charArrayOf('\r', '\n'), i)
                if (newline < 0) return -1
                i = newline + 1
            }
            content.startsWith("/*", i) -> {
                val end = content.indexOf("*/", i + 2)
                if (end < 0) return -1
                i = end + 2
            }
            else -> return -1
        }
    }
    return -1
}


/**
 * The page pip is pointed at for prebuilt Android wheels, one per Python minor.
 *
 * A GitHub Pages URL and deliberately not a release asset: GitHub serves every
 * release asset as `application/octet-stream`, and pip accepts only `text/html`
 * and the two Simple-API types, skipping anything else with a warning and
 * carrying on. The install would then succeed from PyPI, without the mirror,
 * and nothing would look wrong. The wheels themselves ARE release assets; this
 * page links to them.
 *
 * Keyed by the interpreter's minor version, because one page serves every APK
 * in circulation: when the bundled Python moves from 3.14 to 3.15, the 3.15
 * page is added beside the 3.14 one, and a device that has not updated keeps
 * reading wheels its interpreter can install.
 *
 * The release tag is baked into the links on that page rather than into this
 * URL, so a new wheelhouse release is published by regenerating the page, not
 * by shipping a new APK. That release is a prerelease, and must stay one:
 * `ToolchainRegistry` resolves `releases/latest` for the toolchain ZIPs,
 * and GitHub moves `latest` onto the newest non-prerelease whenever the release
 * holding it is withdrawn, whatever `make_latest` said when it was created.
 */
internal fun wheelhouseUrl(pythonMinor: String) =
    "https://rmyndharis.github.io/VSCodroid/wheels/$pythonMinor/wheels.html"


/** First line of the pip.conf this app owns; a file without it is the user's. */
internal const val PIP_CONF_HEADER = "# Written by VSCodroid on every launch."


internal fun pipConfigContent(pythonMinor: String): String = """$PIP_CONF_HEADER
# Your own settings belong in ~/.config/pip/pip.conf, which pip reads after this
# file and which overrides any key set here.
[global]
find-links = ${wheelhouseUrl(pythonMinor)}
prefer-binary = true
"""


/**
 * The OpenSSL configuration [FirstRunSetup.setupOpensslConfig] writes and
 * `OPENSSL_CONF` names.
 *
 * The smallest complete file: it activates the default provider, which is what
 * OpenSSL 3 uses when no configuration says otherwise, so a process that was
 * already starting behaves as before. Node looks for a `nodejs_conf` section
 * and is content when there is none, as it is with Termux's own file. The
 * first two lines are for whoever finds the file and wonders why it exists.
 */
internal val OPENSSL_CONF_CONTENT = """
    # Written by VSCodroid on every launch; edits here do not survive one.
    # Named by OPENSSL_CONF because the bundled libcrypto's compiled-in default
    # is another app's directory, which this app may not read.
    openssl_conf = openssl_init

    [openssl_init]
    providers = provider_sect

    [provider_sect]
    default = default_sect

    [default_sect]
    activate = 1
""".trimIndent() + "\n"
