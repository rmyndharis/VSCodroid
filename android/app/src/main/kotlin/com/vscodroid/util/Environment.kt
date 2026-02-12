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

        // Use bundled bash if available, otherwise fall back to system shell
        val shell = if (File("$nativeLibDir/libbash.so").exists())
            "$nativeLibDir/libbash.so"
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
        val basePath = "$nativeLibDir:$filesDir/usr/bin"
        val path = if (extraPath != null)
            "$basePath:$extraPath:/system/bin"
        else
            "$basePath:/system/bin"

        // Preload script that fixes process.platform ("android" → "linux")
        // so npm packages (Prisma, node-gyp, esbuild, etc.) detect the platform correctly.
        // NODE_OPTIONS propagates to all child processes and worker threads.
        val platformFixPath = "$filesDir/server/platform-fix.js"
        val nodeOptions = "--require=$platformFixPath"

        val base = mapOf(
            "HOME" to homeDir,
            "TMPDIR" to "$cacheDir/tmp",
            "PATH" to path,
            "LD_LIBRARY_PATH" to "$nativeLibDir:$filesDir/usr/lib",
            "NODE_PATH" to "$filesDir/server/vscode-reh/node_modules",
            "NODE_OPTIONS" to nodeOptions,
            "SHELL" to shell,
            "TERM" to term,
            "TERMINFO" to "$filesDir/usr/share/terminfo",
            "LANG" to "en_US.UTF-8",
            "PREFIX" to "$filesDir/usr",
            "PYTHONHOME" to "$filesDir/usr",
            "PYTHONDONTWRITEBYTECODE" to "1",
            "GIT_EXEC_PATH" to "$filesDir/usr/lib/git-core",
            "GIT_TEMPLATE_DIR" to "$filesDir/usr/share/git-core/templates",
            "GIT_SSL_CAPATH" to getSystemCaCertsPath(),
            "SSL_CERT_DIR" to getSystemCaCertsPath(),
            "NPM_CONFIG_PREFIX" to "$filesDir/usr",
            "NPM_CONFIG_CACHE" to "$cacheDir/npm-cache",
            "PROJECTS_DIR" to getProjectsDir(context),
            "VSCODROID_PORT" to port.toString(),
            "VSCODROID_VERSION" to getVersionName(context),
        )

        return base + toolchainEnv
    }

    private fun getToolchainEnvironment(context: Context): MutableMap<String, String> {
        return try {
            ToolchainManager(context).getAllToolchainEnv().toMutableMap()
        } catch (e: Exception) {
            // Toolchain state file may not exist yet — not an error
            mutableMapOf()
        }
    }

    fun getNodePath(context: Context): String =
        "${context.applicationInfo.nativeLibraryDir}/libnode.so"

    fun getServerScript(context: Context): String =
        "${context.filesDir}/server/server.js"

    fun getProjectsDir(context: Context): String {
        // App-external storage: no permissions needed, visible in file managers
        // Path: /storage/emulated/0/Android/data/<pkg>/files/projects
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

    fun getUserDataDir(context: Context): String =
        "${context.filesDir}/home/.vscodroid"

    fun getExtensionsDir(context: Context): String =
        "${context.filesDir}/home/.vscodroid/extensions"

    fun getLogsDir(context: Context): String =
        "${context.filesDir}/home/.vscodroid/data/logs"

    fun getServerDir(context: Context): String =
        "${context.filesDir}/server"

    fun getBashPath(context: Context): String =
        "${context.applicationInfo.nativeLibraryDir}/libbash.so"

    fun getGitPath(context: Context): String =
        "${context.applicationInfo.nativeLibraryDir}/libgit.so"

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
            .take(6) // 6 bytes = 12 hex chars — collision probability ~1 in 281 trillion
            .joinToString("") { "%02x".format(it) }
        return "${getSafMirrorsDir(context)}/$hash"
    }
}
