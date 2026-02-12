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
        val description: String,
        /** Approximate .deb download size in bytes (shown to user before download) */
        val estimatedSize: Long,
        /** Fallback URL for sideloaded installs (no Play Store). Null = Play-only. */
        val downloadUrl: String? = null,
    )

    val available = listOf(
        ToolchainInfo(
            packName = "toolchain_go",
            displayName = "Go",
            description = "Go programming language (CGO_ENABLED=0)",
            estimatedSize = 30_000_000,
        ),
        ToolchainInfo(
            packName = "toolchain_ruby",
            displayName = "Ruby",
            description = "Ruby with irb, gem, bundler",
            estimatedSize = 12_000_000,
        ),
        ToolchainInfo(
            packName = "toolchain_java",
            displayName = "Java 17",
            description = "OpenJDK 17 (javac, jar, jshell)",
            estimatedSize = 96_000_000,
        ),
    )

    /** Look up toolchain info by pack name (e.g. "toolchain_go") or short name (e.g. "go"). */
    fun find(nameOrPack: String): ToolchainInfo? =
        available.find { it.packName == nameOrPack || it.packName == "toolchain_$nameOrPack" }
}
