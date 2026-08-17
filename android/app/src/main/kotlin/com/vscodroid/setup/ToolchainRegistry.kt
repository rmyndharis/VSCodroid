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
        /** Short label for the toolchain card (e.g. "Go", "Ruby", "Java 17"). */
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

    val available = listOf(
        ToolchainInfo(
            packName = "toolchain_go",
            displayName = "Go",
            shortLabel = "Go",
            // The second sentence is not padding, and removing it would put the
            // picker back to selling 179 MB for something the user cannot use as
            // advertised. Android refuses to execute a file under the app's data
            // directory, so binaries are reached through a bash function that
            // hands them to the system loader. A function reaches only as far as
            // the shell: `go build` and `go run` start the compiler, assembler
            // and linker themselves, those forks hit the binaries directly, and
            // they are refused. The card renders this text verbatim
            // (ToolchainPickerAdapter), so it is the only place a user is told
            // before choosing.
            description = "Go programming language (CGO_ENABLED=0). Runs on device, " +
                "but cannot compile: go build and go run are refused by Android.",
            estimatedSize = 179_000_000,
            downloadSize = 59_800_000,
            downloadUrl = "https://github.com/rmyndharis/VSCodroid/releases/latest/download/toolchain_go.zip",
        ),
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

    /** Look up toolchain info by pack name (e.g. "toolchain_go") or short name (e.g. "go"). */
    fun find(nameOrPack: String): ToolchainInfo? =
        available.find { it.packName == nameOrPack || it.packName == "toolchain_$nameOrPack" }

    /** Format byte count as human-readable size (e.g. "179 MB"). */
    fun formatSize(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "${bytes / 1_000_000_000} GB"
        bytes >= 1_000_000 -> "${bytes / 1_000_000} MB"
        bytes >= 1_000 -> "${bytes / 1_000} KB"
        else -> "$bytes B"
    }
}
