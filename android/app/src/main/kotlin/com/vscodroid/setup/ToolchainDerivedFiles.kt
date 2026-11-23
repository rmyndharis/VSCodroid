package com.vscodroid.setup

import android.system.Os
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import com.vscodroid.webview.redactToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Generates derived files from `toolchains.json`:
 *   - `home/.vscodroid/toolchain-env.sh` (sourced by `.bashrc` and `BASH_ENV`)
 *   - `home/.vscodroid/toolchain-exec.tsv` (read by the native execution trampoline)
 *   - trampoline symlinks in `usr/libexec/tcbin/`, git helper wrappers, npm/codex launchers, and server node link
 *   - resolved environment variables for [ToolchainManager.getAllToolchainEnv]
 */
internal class ToolchainDerivedFiles(private val manager: ToolchainManager) {

    private companion object {
        /** Where the built-in git extension keeps the helper scripts it exports. */
        private const val GIT_EXTENSION_DIST = "server/vscode-reh/extensions/git/dist"

        /** The server tree, whose `node` the remote CLI runs. */
        private const val SERVER_TREE = "server/vscode-reh"

        /**
         * Where the server keeps its command-line client, the directory it puts
         * first on every terminal's PATH.
         */
        private const val REMOTE_CLI_DIR = "$SERVER_TREE/bin/remote-cli"

        /** The client's name there, which is `applicationName` in the product config. */
        private const val REMOTE_CLI = "vscodroid"

        /**
         * Every script the git extension may name in `GIT_ASKPASS`, `SSH_ASKPASS`
         * or `GIT_EDITOR`; the `-empty` ones are what it names when its IPC
         * server is off. A name missing from the shipped tree is skipped.
         */
        private val GIT_HELPER_SCRIPTS = listOf(
            "askpass.sh", "askpass-empty.sh",
            "ssh-askpass.sh", "ssh-askpass-empty.sh",
            "git-editor.sh", "git-editor-empty.sh",
        )

        private const val GIT_CORE_DIR = "usr/lib/git-core"
        private val GIT_CORE_SCRIPTS = listOf(
            "git-submodule", "git-mergetool", "git-filter-branch", "git-subtree",
            "git-merge-octopus", "git-merge-one-file", "git-merge-resolve",
            "git-difftool--helper", "git-quiltimport", "git-request-pull", "git-web--browse",
        )

        /** Appended to a git helper's name for the shipped script its link replaced. */
        private const val GIT_HELPER_SUFFIX = ".script"

        /**
         * What runs a git helper or an npm launcher. The scripts are POSIX `sh`,
         * and this is a file the app may execute, which the trampoline hands to
         * the loader with the script as its argument.
         */
        private const val SYSTEM_SHELL = "/system/bin/sh"

        /**
         * Path to the system dynamic linker, which is the only way a toolchain
         * binary runs at all under SELinux enforcing (`execute_no_trans` denied on `app_data_file`).
         */
        private const val SYSTEM_LOADER = "/system/bin/linker64"

        /**
         * The Codex engine, relative to filesDir. Under `lib/` of the install
         * root, which [isPathCommand] keeps off PATH: the command `codex` is the
         * launcher [addCodexRows] writes, and the helper the engine starts sits
         * beside this file.
         */
        private const val CODEX_ENGINE = "usr/libexec/codex/lib/codex"

        /**
         * Environment variables that hold a list of options the caller may already have
         * set, which a toolchain adds to rather than replaces.
         */
        private val OPTION_LIST_VARS = setOf("RUBYOPT")

        /** `-XX:Name=value`, `-XX:+Name`, `-XX:-Name`, and the heap and stack sizes. */
        private val JVM_FLAG = Regex("-(?:XX:(?:[+-][A-Za-z0-9_]+|[A-Za-z0-9_]+=[A-Za-z0-9_.+-]+)|X(?:mx|ms|ss)[0-9]+[kKmMgG]?)")

        /** How much of a file in `usr/bin` is read to find out what runs it. */
        private const val SHEBANG_HEAD_BYTES = 256

        fun jvmFlagsOf(manifest: JSONObject): List<String> {
            val flags = manifest.optJSONArray("jvmFlags") ?: return emptyList()
            return (0 until flags.length())
                .map { flags.optString(it, "") }
                .filter { JVM_FLAG.matches(it) }
        }

        fun jvmLauncherFlags(manifest: JSONObject, relPath: String): List<String> {
            val root = manifest.optString("installRoot", "")
            if (root.isEmpty() || !relPath.startsWith("$root/bin/")) return emptyList()
            val direct = relPath.substringAfterLast('/') == "java"
            return jvmFlagsOf(manifest).map { if (direct) it else "-J$it" }
        }

        fun isPathCommand(manifest: JSONObject, relPath: String): Boolean {
            val root = manifest.optString("installRoot", "")
            return root.isEmpty() || !relPath.startsWith("$root/lib/")
        }
    }

    /**
     * Everything derived from `toolchains.json`, regenerated together.
     * Caller must hold [ToolchainManager.stateLock].
     */
    fun regenerateDerivedFilesLocked() {
        regenerateEnvFileLocked()
        regenerateExecTableLocked()
    }

    /**
     * Caller must hold [ToolchainManager.stateLock].
     */
    private fun regenerateEnvFileLocked() {
        val installed = manager.readableStateFile()
        if (installed == null) {
            Logger.w(manager.tag, "toolchains.json is unreadable; keeping the env file as it stands")
            return
        }
        if (installed.length() == 0) {
            if (manager.envFile.exists()) manager.envFile.delete()
            return
        }

        val sb = StringBuilder()
        sb.appendLine("# Auto-generated by ToolchainManager: do not edit")
        sb.appendLine("# Sourced by .bashrc for toolchain environment variables")
        sb.appendLine()

        val extraPaths = mutableListOf<String>()

        for (i in 0 until installed.length()) {
            val tc = installed.optJSONObject(i) ?: continue
            val name = tc.optString("name", "unknown")
            val env = tc.optJSONObject("env") ?: continue
            val preloadOnly = isPreloadOnly(tc)

            sb.appendLine("# $name toolchain")
            for (key in env.keys()) {
                val value = env.getString(key)
                    .replace("\$FILESDIR", "\$PREFIX/..")
                if (!isShellSafeEnvEntry(key, value)) {
                    Logger.w(
                        manager.tag,
                        "No export for $name's ${redactToken(key)}: not a line that is " +
                            "safe to put in a file every terminal sources",
                    )
                    continue
                }
                if (key in OPTION_LIST_VARS) {
                    sb.appendLine(
                        "case \" \${$key-} \" in *\" $value \"*) ;; " +
                            "*) export $key=\"\${$key:+\$$key }$value\" ;; esac"
                    )
                } else {
                    sb.appendLine("export $key=\"$value\"")
                }
            }
            sb.appendLine()

            val scriptNames = tc.optJSONObject("scriptWrappers")
                ?.optJSONObject("scripts")?.keys()?.asSequence()?.toSet().orEmpty()
            val binaries = if (preloadOnly) null else tc.optJSONArray("binaries")
            if (preloadOnly) {
                sb.appendLine("# $name programs start through the terminal's preloaded exec, so no wrappers")
                sb.appendLine()
            }
            if (binaries != null) {
                val lines = mutableListOf<String>()
                for (j in 0 until binaries.length()) {
                    val relPath = binaries.getString(j)
                    val command = relPath.substringAfterLast('/')
                    if (command.isEmpty() || command in scriptNames || !isPathCommand(tc, relPath)) continue
                    val toolFlags = jvmLauncherFlags(tc, relPath).joinToString("") { "$it " }
                    if (!isShellFunctionName(command)) {
                        Logger.w(
                            manager.tag,
                            "No wrapper for $command: not a name bash can " +
                                "use as a function, so it would break every terminal",
                        )
                        continue
                    }
                    if (!isElfFile(File(manager.context.filesDir, relPath))) continue
                    if (command == "jshell") {
                        lines.add(
                            "jshell() { local arg skip= chosen=; for arg in \"\$@\"; do " +
                                "case \"\$arg\" in -J*) continue ;; esac; " +
                                "if [ -n \"\$skip\" ]; then skip=; continue; fi; " +
                                "case \"\$arg\" in --) break ;; -ex*|--ex*) chosen=1; break ;; *=*) ;; " +
                                "--c*|--m*|--add-[em]*|--st*|--fe*|--[RC]|-add-[em]*|-st*|-fe*) skip=1 ;; " +
                                "-c*) case class-path in \"\${arg#-}\"*) skip=1 ;; esac ;; " +
                                "-m*) case module-path in \"\${arg#-}\"*) skip=1 ;; esac ;; " +
                                "-*[RCcm]) case \"\${arg%?}\" in -*[!nqsvhX?]*) ;; *) skip=1 ;; esac ;; " +
                                "esac; done; " +
                                "[ -n \"\$chosen\" ] || set -- --execution local \"\$@\"; " +
                                "$SYSTEM_LOADER \"\$PREFIX/../$relPath\" " +
                                toolFlags +
                                "-J-Djdk.lang.Process.launchMechanism=FORK " +
                                "-J-Duser.home=\"\$HOME\" \"\$@\"; }"
                        )
                        continue
                    }
                    val properties = when (command) {
                        "java" -> "\${HOME:+\"-Duser.home=\$HOME\"} \${TMPDIR:+\"-Djava.io.tmpdir=\$TMPDIR\"}"
                        "keytool" -> "\${HOME:+\"-J-Duser.home=\$HOME\"}"
                        else -> null
                    }
                    appendToolchainBinaryWrapperLine(
                        lines = lines,
                        command = command,
                        systemLoader = SYSTEM_LOADER,
                        relPath = relPath,
                        toolFlags = toolFlags,
                        properties = properties,
                    )
                }
                if (lines.isNotEmpty()) {
                    sb.appendLine("# $name binaries (SELinux blocks exec under filesDir)")
                    lines.forEach(sb::appendLine)
                    sb.appendLine()
                }
            }

            val scriptWrappers = if (preloadOnly) null else tc.optJSONObject("scriptWrappers")
            if (scriptWrappers != null) {
                val interpreter = scriptWrappers.optString("interpreter", "")
                val scripts = scriptWrappers.optJSONObject("scripts")
                if (interpreter.isNotEmpty() && scripts != null) {
                    val lines = mutableListOf<String>()
                    for (scriptName in scripts.keys()) {
                        if (!isShellFunctionName(scriptName)) {
                            Logger.w(
                                manager.tag,
                                "No wrapper for $scriptName: not a name bash can " +
                                    "use as a function, so it would break every terminal",
                            )
                            continue
                        }
                        val scriptPath = scripts.getString(scriptName)
                        appendToolchainScriptWrapperLine(
                            lines = lines,
                            scriptName = scriptName,
                            interpreter = interpreter,
                            scriptPath = scriptPath,
                        )
                    }
                    if (lines.isNotEmpty()) {
                        sb.appendLine("# $name script wrappers (SELinux blocks exec under filesDir)")
                        lines.forEach(sb::appendLine)
                        sb.appendLine()
                    }
                }
            }

            val pathDirs = tc.optJSONArray("pathDirs")
            if (pathDirs != null) {
                for (j in 0 until pathDirs.length()) {
                    val dir = pathDirs.getString(j)
                    if (!isShellSafePathDir(dir)) {
                        Logger.w(
                            manager.tag,
                            "No PATH entry for $name's ${redactToken(dir)}: not a directory " +
                                "name that is safe to put in a file every terminal sources",
                        )
                        continue
                    }
                    extraPaths.add("\$PREFIX/../$dir")
                }
            }
        }

        if (extraPaths.isNotEmpty()) {
            sb.appendLine("# Toolchain PATH additions")
            sb.appendLine(
                "for __vscodroid_d in ${extraPaths.distinct().joinToString(" ") { "\"$it\"" }}; do " +
                    "case \":\$PATH:\" in *\":\$__vscodroid_d:\"*) ;; *) PATH=\"\$PATH:\$__vscodroid_d\" ;; esac; done; " +
                    "export PATH; unset __vscodroid_d"
            )
        }

        manager.envFile.parentFile?.mkdirs()
        if (!writeAtomically(manager.envFile) { it.write(sb.toString().toByteArray()) }) {
            Logger.e(manager.tag, "Could not write toolchain-env.sh; it still holds the previous environment")
            return
        }
        Logger.i(manager.tag, "Regenerated toolchain-env.sh (${installed.length()} toolchains)")
    }

    /**
     * Writes the table the execution trampoline reads, then repoints the
     * symlinks that put its names on PATH.
     *
     * Caller must hold [ToolchainManager.stateLock].
     */
    private fun regenerateExecTableLocked() {
        val readable = manager.readableStateFile()
        val rows = LinkedHashMap<String, String>()
        var gemBin: Pair<File, String>? = null
        val envRows = LinkedHashMap<String, String>()
        if (readable == null) {
            Logger.w(manager.tag, "toolchains.json is unreadable; carrying the previous toolchain rows")
            manager.execTable.takeIf { it.isFile }?.readLines()?.forEach { line ->
                val fields = line.split('\t')
                if (fields.size < 2) return@forEach
                if (fields[0].isEmpty()) {
                    envRows[fields[1]] = line
                } else if (fields.drop(1).filter { !it.startsWith("-") }
                        .all { it.startsWith("${manager.filesDir}/") && File(it).exists() }
                ) {
                    rows[fields[0]] = line
                }
            }
        }
        val installed = readable ?: JSONArray()
        for (i in 0 until installed.length()) {
            val tc = installed.optJSONObject(i) ?: continue
            val name = tc.optString("name", "unknown")
            val scriptNames = tc.optJSONObject("scriptWrappers")
                ?.optJSONObject("scripts")?.keys()?.asSequence()?.toSet().orEmpty()

            val env = tc.optJSONObject("env")
            if (env != null) {
                for (key in env.keys()) {
                    val value = expandToolchainValue(manager.filesDir, manager.homeDir, env.getString(key))
                    if (key.isEmpty() || key.any { it == '=' || it == '\t' || it == '\n' } ||
                        '\n' in value
                    ) {
                        Logger.w(
                            manager.tag,
                            "No environment row for $name's $key: not a name the table can carry",
                        )
                        continue
                    }
                    envRows[key] = "\t$key\t$value"
                }
            }

            val preloadOnly = isPreloadOnly(tc)
            val elfPaths = mutableMapOf<String, String>()
            val binaries = if (preloadOnly) null else tc.optJSONArray("binaries")
            if (binaries != null) {
                for (j in 0 until binaries.length()) {
                    val relPath = binaries.getString(j)
                    val command = relPath.substringAfterLast('/')
                    if (command.isEmpty() || command in scriptNames || !isPathCommand(tc, relPath)) continue
                    if (!isElfFile(File(manager.context.filesDir, relPath))) continue
                    val absolute = "${manager.filesDir}/$relPath"
                    elfPaths[command] = absolute
                    val args = jdkLauncherArgs(tc, relPath, command)
                    if (!fitsTableRow(args)) {
                        Logger.w(
                            manager.tag,
                            "No row for $name's $command: its options are not a list of at most " +
                                "${ToolchainManager.MAX_ROW_ARGS} the table can carry",
                        )
                        continue
                    }
                    rows[command] = (listOf(command, absolute) + args).joinToString("\t")
                }
            }

            val scriptWrappers = if (preloadOnly) null else tc.optJSONObject("scriptWrappers")
            val interpreter = scriptWrappers?.optString("interpreter", "").orEmpty()
            val scripts = scriptWrappers?.optJSONObject("scripts")
            if (interpreter.isNotEmpty() && scripts != null) {
                val interpreterPath = elfPaths[interpreter]
                if (interpreterPath == null) {
                    Logger.w(
                        manager.tag,
                        "No exec-table rows for $name scripts: its interpreter " +
                            "$interpreter is not an ELF this manifest ships",
                    )
                } else {
                    for (scriptName in scripts.keys()) {
                        val scriptPath = scripts.getString(scriptName)
                        if (!File(manager.context.filesDir, scriptPath).isFile) {
                            Logger.w(manager.tag, "No row for $name's $scriptName: $scriptPath is not on disk")
                            continue
                        }
                        rows[scriptName] = "$scriptName\t$interpreterPath\t${manager.filesDir}/$scriptPath"
                    }
                }
            }

            addLauncherRows(tc, name, rows)

            val gemHome = env?.optString("GEM_HOME", "").orEmpty()
            val ruby = elfPaths["ruby"]
            if (gemHome.isNotEmpty() && ruby != null) {
                gemBin = File(expandToolchainValue(manager.filesDir, manager.homeDir, gemHome), "bin") to ruby
            }
        }

        rows.putIfAbsent(
            "xdg-open",
            "xdg-open\t${Environment.getNodePath(manager.context)}\t${manager.filesDir}/server/xdg-open.js",
        )
        addNpmRows(rows)
        addCodexRows(rows, installed, stateKnown = readable != null)
        val setAside = addGitHelperRows(rows, GIT_EXTENSION_DIST, GIT_HELPER_SCRIPTS) +
            addGitHelperRows(rows, REMOTE_CLI_DIR, listOf(REMOTE_CLI)) +
            addGitHelperRows(rows, GIT_CORE_DIR, GIT_CORE_SCRIPTS)
        linkServerNode()
        val binDir = File(manager.context.filesDir, "usr/bin")
        addInstalledScriptRows(rows, binDir, File(binDir, "python3").absolutePath, "python", "pip")
        gemBin?.let { (dir, ruby) -> addInstalledScriptRows(rows, dir, ruby, "ruby", "gem") }

        val body = (envRows.values + rows.values).joinToString("\n", postfix = "\n")
        manager.execTable.parentFile?.mkdirs()
        if (!writeAtomically(manager.execTable) { it.write(body.toByteArray()) }) {
            Logger.e(manager.tag, "Could not write toolchain-exec.tsv; it still holds the previous table")
            return
        }
        refreshTrampolineLinks(rows.keys - setAside)
        Logger.i(
            manager.tag,
            "Regenerated toolchain-exec.tsv (${rows.size} commands, ${envRows.size} variables)",
        )
    }

    /**
     * The options a JDK launcher's exec-table row carries, matching [regenerateEnvFileLocked].
     */
    private fun jdkLauncherArgs(manifest: JSONObject, relPath: String, command: String): List<String> {
        val args = jvmLauncherFlags(manifest, relPath).toMutableList()
        val tmpDir = "${manager.context.cacheDir.absolutePath}/tmp"
        when (command) {
            "java" -> args += listOf("-Duser.home=${manager.homeDir}", "-Djava.io.tmpdir=$tmpDir")
            "keytool" -> args += "-J-Duser.home=${manager.homeDir}"
            "jshell" -> args += listOf("-J-Djdk.lang.Process.launchMechanism=FORK", "-J-Duser.home=${manager.homeDir}")
        }
        return args
    }

    /**
     * Rows for the binaries a manifest's `launchers` object names, each with the
     * arguments the row carries ahead of the caller's own.
     */
    private fun addLauncherRows(tc: JSONObject, name: String, rows: MutableMap<String, String>) {
        val launchers = tc.optJSONObject("launchers") ?: return
        val shipped = tc.optJSONArray("binaries")
            ?.let { list -> (0 until list.length()).map { list.optString(it) }.toSet() }.orEmpty()
        for (relPath in launchers.keys()) {
            val command = relPath.substringAfterLast('/')
            val argList = launchers.optJSONArray(relPath)
            val args = argList?.let { list ->
                (0 until list.length()).map {
                    expandToolchainValue(manager.filesDir, manager.homeDir, list.optString(it))
                }
            }
            when {
                command.isEmpty() || relPath !in shipped ->
                    Logger.w(manager.tag, "No launcher row for $name's $relPath: not one of its binaries")
                args == null || !fitsTableRow(args) ->
                    Logger.w(
                        manager.tag,
                        "No launcher row for $name's $command: its arguments " +
                            "are not a list of at most ${ToolchainManager.MAX_ROW_ARGS} the table can carry",
                    )
                !isElfFile(File(manager.context.filesDir, relPath)) ->
                    Logger.d(manager.tag, "No launcher row for $name's $command: $relPath is not on disk")
                else -> rows[command] = (listOf(command, "${manager.filesDir}/$relPath") + args).joinToString("\t")
            }
        }
    }

    private fun fitsTableRow(args: List<String>): Boolean =
        args.size <= ToolchainManager.MAX_ROW_ARGS && args.none { '\t' in it || '\n' in it }

    /**
     * Gives `npm` and `npx` rows, so a program that starts either by name finds it.
     */
    private fun addNpmRows(rows: LinkedHashMap<String, String>) {
        val cliDir = "${manager.filesDir}/usr/lib/node_modules/npm/bin"
        val node = Environment.getNodePath(manager.context)
        val platformFix = "${manager.filesDir}/server/platform-fix.js"
        for ((command, cli, flags) in listOf(
            Triple("npm", "npm-cli.js", " --prefer-offline"),
            Triple("npx", "npx-cli.js", ""),
        )) {
            if (command in rows || !File(cliDir, cli).isFile) continue
            val launcher = File(manager.execTable.parentFile, "$command.sh")
            val body = """
                |# Auto-generated by ToolchainManager: do not edit
                |# $command for a program that starts it by name, run through the exec
                |# trampoline with /system/bin/sh. Bash runs the $command() function instead.
                |case " ${'$'}{NODE_OPTIONS-} " in
                |    *"/server/platform-fix.js"*) ;;
                |    *) export NODE_OPTIONS="--require=$platformFix${'$'}{NODE_OPTIONS:+ ${'$'}NODE_OPTIONS}" ;;
                |esac
                |export VSCODROID_PLATFORM_FIX=1
                |exec '$node' '$cliDir/$cli'$flags "${'$'}@"
                |""".trimMargin()
            launcher.parentFile?.mkdirs()
            if (runCatching { launcher.readText() }.getOrNull() != body &&
                !writeAtomically(launcher) { it.write(body.toByteArray()) }
            ) {
                Logger.w(manager.tag, "Could not write ${launcher.name}; $command stays a bash function only")
                continue
            }
            rows[command] = "$command\t$SYSTEM_SHELL\t${launcher.absolutePath}"
        }
    }

    /**
     * The `codex` command launcher, starting the engine through the loader with its preloads.
     */
    private fun addCodexRows(rows: LinkedHashMap<String, String>, installed: JSONArray, stateKnown: Boolean) {
        val engine = File(manager.context.filesDir, CODEX_ENGINE)
        val launcher = File(manager.execTable.parentFile, "codex.sh")
        val recorded = if (stateKnown) {
            (0 until installed.length()).any { installed.optJSONObject(it)?.optString("name") == "codex" }
        } else {
            launcher.isFile
        }
        if ("codex" in rows || !recorded || !engine.isFile) {
            launcher.delete()
            return
        }
        val preload = Environment.getExecPreloadPath(manager.context)
        val selfExe = Environment.getSelfExePreloadPath(manager.context)
        val body = """
            |# Auto-generated by ToolchainManager: do not edit
            |# The Codex engine, started through the loader with what it needs, for a
            |# program that starts it by name (the extension) and for a terminal.
            |engine='${engine.absolutePath}'
            |for file in "${'$'}engine" '$preload' '$selfExe'; do
            |    [ -f "${'$'}file" ] || { echo "codex: ${'$'}file is missing" >&2; exit 127; }
            |done
            |TERMUX_EXEC__PROC_SELF_EXE="${'$'}engine" LD_PRELOAD='$preload:$selfExe' exec $SYSTEM_LOADER "${'$'}engine" "${'$'}@"
            |""".trimMargin()
        launcher.parentFile?.mkdirs()
        if (runCatching { launcher.readText() }.getOrNull() != body &&
            !writeAtomically(launcher) { it.write(body.toByteArray()) }
        ) {
            Logger.w(manager.tag, "Could not write ${launcher.name}; codex stays off PATH")
            return
        }
        rows["codex"] = "codex\t$SYSTEM_SHELL\t${launcher.absolutePath}"
    }

    /**
     * Gives every command `pip` or `gem` has installed a row, so it can run via the trampoline.
     */
    private fun addInstalledScriptRows(
        rows: LinkedHashMap<String, String>,
        binDir: File,
        interpreter: String,
        shebangWord: String,
        installer: String,
    ) {
        val names = binDir.list() ?: return
        if (!File(interpreter).exists()) return

        var added = 0
        for (name in names.sorted()) {
            if (name in rows) continue
            if (name.any { it == '\t' || it == '\n' }) {
                Logger.w(
                    manager.tag,
                    "No row for '" + name.replace("\t", "\\t").replace("\n", "\\n") +
                        "': a command name carrying a tab or a newline cannot be written " +
                        "to the exec table, which splits a row on its tabs",
                )
                continue
            }
            val script = File(binDir, name)
            val isSymlink = try {
                Os.readlink(script.absolutePath); true
            } catch (_: Exception) {
                false
            }
            if (isSymlink || !script.isFile) continue
            if (!shebangNames(script, shebangWord)) continue
            rows[name] = "$name\t$interpreter\t${script.absolutePath}"
            added++
        }
        if (added > 0) Logger.i(manager.tag, "Exec table carries $added $installer-installed commands")
    }

    private fun shebangNames(script: File, word: String): Boolean = try {
        script.inputStream().use { stream ->
            val head = ByteArray(SHEBANG_HEAD_BYTES)
            val read = stream.read(head)
            if (read < 3) {
                false
            } else {
                val first = String(head, 0, read, Charsets.ISO_8859_1).substringBefore('\n')
                first.startsWith("#!") && first.contains(word)
            }
        }
    } catch (e: Exception) {
        Logger.d(manager.tag, "Could not read ${script.name} to see what runs it: ${e.message}")
        false
    }

    private fun refreshTrampolineLinks(commands: Set<String>) {
        try {
            refreshTrampolineLinksLocked(commands)
        } catch (e: Exception) {
            Logger.w(manager.tag, "Could not refresh the toolchain trampoline links: ${e.message}")
        }
    }

    /**
     * Makes bundled shell helper scripts runnable, and answers the names it gave rows to.
     */
    private fun addGitHelperRows(
        rows: LinkedHashMap<String, String>,
        dir: String,
        names: List<String>,
    ): Set<String> {
        val dist = File(manager.context.filesDir, dir)
        if (!dist.isDirectory) return emptySet()
        val target = Environment.getTrampolinePath(manager.context)
        val served = mutableSetOf<String>()
        for (name in names) {
            val link = File(dist, name)
            val kept = File(dist, name + GIT_HELPER_SUFFIX)
            val linked = holdingWriteLock(link) {
                val current = try { Os.readlink(link.absolutePath) } catch (e: Exception) { null }
                if (current == null && link.isFile && !link.renameTo(kept)) {
                    Logger.w(manager.tag, "Could not set $name aside; it cannot run")
                    return@holdingWriteLock false
                }
                kept.isFile && (current == target || relink(link, target))
            }
            if (!linked) continue
            val arguments = if (dir == GIT_CORE_DIR) {
                "-c\t. \"\$0$GIT_HELPER_SUFFIX\"\t${link.absolutePath}"
            } else kept.absolutePath
            rows.putIfAbsent(name, "$name\t$SYSTEM_SHELL\t$arguments")
            served += name
        }
        return served
    }

    private fun refreshTrampolineLinksLocked(commands: Set<String>) {
        val target = Environment.getTrampolinePath(manager.context)
        if (commands.isNotEmpty() && !manager.tcBinDir.exists() && !manager.tcBinDir.mkdirs()) {
            Logger.w(
                manager.tag,
                "Could not create ${manager.tcBinDir.absolutePath}; " +
                    "toolchain commands stay unreachable to anything but bash",
            )
            return
        }

        var created = 0
        var updated = 0
        for (command in commands) {
            val link = File(manager.tcBinDir, command)
            val current = try { Os.readlink(link.absolutePath) } catch (e: Exception) { null }
            if (current == target) continue

            val staging = File(manager.tcBinDir, ".$command.tmp~")
            staging.delete()
            try {
                Os.symlink(target, staging.absolutePath)
                Os.rename(staging.absolutePath, link.absolutePath)
                if (current == null) created++ else updated++
            } catch (e: Exception) {
                staging.delete()
                Logger.d(manager.tag, "Failed to link $command to the trampoline: ${e.message}")
            }
        }

        var removed = 0
        for (entry in manager.tcBinDir.listFiles().orEmpty()) {
            if (entry.name in commands) continue
            if (entry.delete()) removed++
        }

        if (created + updated + removed > 0) {
            Logger.i(
                manager.tag,
                "Trampoline links: $created created, $updated repointed, " +
                    "$removed removed in usr/libexec/tcbin/",
            )
        }
    }

    /** Links the server tree's `node` to the app's own Node, for the remote CLI. */
    private fun linkServerNode() {
        val tree = File(manager.context.filesDir, SERVER_TREE)
        if (!tree.isDirectory) return
        val node = File(tree, "node")
        val target = Environment.getNodePath(manager.context)
        val current = try { Os.readlink(node.absolutePath) } catch (e: Exception) { null }
        if (current != target) relink(node, target)
    }

    private fun relink(link: File, target: String): Boolean {
        val staging = File(link.parentFile, ".${link.name}.tmp~")
        staging.delete()
        return try {
            Os.symlink(target, staging.absolutePath)
            Os.rename(staging.absolutePath, link.absolutePath)
            true
        } catch (e: Exception) {
            staging.delete()
            Logger.w(manager.tag, "Could not link ${link.name} to $target: ${e.message}")
            false
        }
    }

    private fun isPreloadOnly(manifest: JSONObject): Boolean =
        manifest.optBoolean("preloadOnly", false)

    /**
     * Returns resolved environment variables for all installed toolchains.
     * Used by `Environment.kt` to include in the Node.js server process env.
     */
    fun getAllToolchainEnv(): Map<String, String> {
        val installed = manager.readStateFile()
        val env = mutableMapOf<String, String>()
        val extraPaths = mutableListOf<String>()

        for (i in 0 until installed.length()) {
            val tc = installed.optJSONObject(i) ?: continue
            val tcEnv = tc.optJSONObject("env") ?: continue

            for (key in tcEnv.keys()) {
                env[key] = expandToolchainValue(manager.filesDir, manager.homeDir, tcEnv.getString(key))
            }

            val pathDirs = tc.optJSONArray("pathDirs")
            if (pathDirs != null) {
                for (j in 0 until pathDirs.length()) {
                    extraPaths.add("${manager.filesDir}/${pathDirs.getString(j)}")
                }
            }
        }

        if (extraPaths.isNotEmpty()) {
            env["__TOOLCHAIN_EXTRA_PATH"] = extraPaths.joinToString(":")
        }

        return env
    }
}
