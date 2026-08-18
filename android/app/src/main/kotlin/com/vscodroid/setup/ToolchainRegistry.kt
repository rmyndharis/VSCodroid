package com.vscodroid.setup

/**
 * Catalog of available on-demand toolchains.
 *
 * Each entry maps to a Play Asset Delivery pack (toolchain_<name>) and a
 * download script (scripts/download-<name>.sh) that populates it at build time.
 */
object ToolchainRegistry {

    data class ToolchainInfo(
        val packName: String,
        val displayName: String,
        /** Short label for the toolchain card (e.g. "Ruby", "Java 17"). */
        val shortLabel: String,
        val description: String,
        /**
         * Approximate size on disk once unpacked, in bytes.
         *
         * This is the free-space figure: `downloadViaHttp` gates on it plus a
         * buffer, because the unpacked tree is what has to fit. It is not what
         * the user is choosing about on mobile data, which is [downloadSize],
         * and telling them this number alone overstated every toolchain by
         * roughly three times.
         */
        val estimatedSize: Long,
        /**
         * Approximate size of the ZIP fetched over HTTP, in bytes.
         *
         * Measured from the release assets rather than computed: a payload is
         * rebuilt whenever its source moves, so both figures here are
         * hand-written and go stale the same way. `downloadFile` deliberately
         * prefers the length the server actually sent and falls back to a
         * constant only when there is none, so nothing depends on this being
         * exact; it exists so the picker can say what a download will cost.
         */
        val downloadSize: Long,
        /** Fallback URL for sideloaded installs (no Play Store). Null = Play-only. */
        val downloadUrl: String? = null,
    )

    /**
     * What the picker and the manage screen offer.
     *
     * Go was here and is not any more. Its card carried the honest sentence,
     * that it runs but cannot compile, and an honest label on 179 MB that does
     * not do the thing people install a compiler for is still 179 MB that does
     * not do it. Android refuses to execute a file under the app's data
     * directory, and `go build` and `go run` fork the compiler, assembler and
     * linker themselves, so those forks are refused however the `go` command is
     * reached. Measured in the app's own SELinux domain: a plain shell script
     * placed there and marked executable is refused too, and `-toolexec`, the
     * one idea that looked like a way round it, never reaches the problem.
     *
     * Removing an entry here is not the whole job. See
     * [ToolchainManager.removeRetiredToolchainsSync]: installs that already
     * have it lose their Remove button along with the entry, so they are swept.
     */
    val available = listOf(
        ToolchainInfo(
            packName = "toolchain_ruby",
            displayName = "Ruby",
            shortLabel = "Ruby",
            description = "Ruby with irb, gem, bundler",
            estimatedSize = 34_000_000,
            downloadSize = 9_900_000,
            downloadUrl = "https://github.com/rmyndharis/VSCodroid/releases/latest/download/toolchain_ruby.zip",
        ),
        ToolchainInfo(
            packName = "toolchain_java",
            displayName = "Java 17",
            shortLabel = "Java 17",
            description = "OpenJDK 17 (javac, jar, jshell)",
            estimatedSize = 146_000_000,
            downloadSize = 55_400_000,
            downloadUrl = "https://github.com/rmyndharis/VSCodroid/releases/latest/download/toolchain_java.zip",
        ),
    )

    /** Look up toolchain info by pack name (e.g. "toolchain_ruby") or short name (e.g. "ruby"). */
    fun find(nameOrPack: String): ToolchainInfo? =
        available.find { it.packName == nameOrPack || it.packName == "toolchain_$nameOrPack" }

    /** Format byte count as human-readable size (e.g. "146 MB"). */
    fun formatSize(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "${bytes / 1_000_000_000} GB"
        bytes >= 1_000_000 -> "${bytes / 1_000_000} MB"
        bytes >= 1_000 -> "${bytes / 1_000} KB"
        else -> "$bytes B"
    }
}
