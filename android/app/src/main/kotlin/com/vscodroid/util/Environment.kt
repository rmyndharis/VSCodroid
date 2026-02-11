package com.vscodroid.util

import android.content.Context
import java.io.File

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

        // Use dumb terminal until real PTY (node-pty native) is available.
        // Pipe-based terminal shim doesn't support ANSI escape sequences.
        val term = if (File("$nativeLibDir/libbash.so").exists())
            "xterm-256color"
        else
            "dumb"

        return mapOf(
            "HOME" to homeDir,
            "TMPDIR" to "$cacheDir/tmp",
            "PATH" to "$nativeLibDir:$filesDir/usr/bin:/system/bin",
            "LD_LIBRARY_PATH" to "$nativeLibDir:$filesDir/usr/lib",
            "NODE_PATH" to "$filesDir/server/vscode-reh/node_modules",
            "SHELL" to shell,
            "TERM" to term,
            "TERMINFO" to "$filesDir/usr/share/terminfo",
            "LANG" to "en_US.UTF-8",
            "PREFIX" to "$filesDir/usr",
            "PYTHON_HOME" to "$filesDir/usr/lib/python3.12",
            "PYTHONHOME" to "$filesDir/usr",
            "PYTHONDONTWRITEBYTECODE" to "1",
            "GIT_EXEC_PATH" to "$filesDir/usr/lib/git-core",
            "GIT_TEMPLATE_DIR" to "$filesDir/usr/share/git-core/templates",
            "GIT_SSL_CAPATH" to getSystemCaCertsPath(),
            "SSL_CERT_DIR" to getSystemCaCertsPath(),
            "PROJECTS_DIR" to getProjectsDir(context),
            "VSCODROID_PORT" to port.toString(),
            "VSCODROID_VERSION" to getVersionName(context),
        )
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
}
