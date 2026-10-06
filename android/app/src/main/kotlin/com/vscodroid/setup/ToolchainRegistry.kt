package com.vscodroid.setup

import androidx.annotation.StringRes
import com.vscodroid.R

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
        /**
         * The sentence under the name on the toolchain card, as a resource id.
         *
         * A resource rather than a string because this is the only field here a
         * user reads as language: [displayName] and [shortLabel] are the
         * products' own names and stay the same in every locale, while this is a
         * sentence that has to change with one. Holding it as a `String` made it
         * unreachable to a translator, since nothing between here and the card
         * goes near a resource.
         */
        @StringRes val descriptionRes: Int,
        /**
         * Approximate size on disk once unpacked, in bytes.
         *
         * This is the free-space figure: `downloadViaHttp` gates on about twice
         * it plus a buffer, because the unpacked tree is what has to fit and a
         * fresh HTTP install holds two copies of it at once. It is not what
         * the user is choosing about on mobile data, which is [downloadSize],
         * and telling them this number alone overstated every toolchain by
         * roughly three times.
         *
         * **Understating it is the direction that costs a user something.**
         * Both install pre-flights key off this one number (`packInstallBytes`
         * for Play, `toolchainInstallBytes` for HTTP), and so does every surface
         * that quotes the figure to the user: the native card
         * ([ToolchainCardState]) and the JSON `getAvailableToolchains` hands the
         * web UI. A count is not given here because a count is what rots; the
         * pre-flights are the readers a wrong figure costs someone something. A
         * figure below the real tree eats the 50 MB buffer the reservations are
         * built on, which admits a device the gate exists to refuse and leaves it
         * out of room partway through the copy. The first-run pre-flight is
         * deliberately not among them: it reads what is on disk rather than what
         * a toolchain claims to need, so nothing there consults this. Measure
         * rather than estimate:
         * `du -sk android/toolchain_<name>/src/main/assets/usr`, which is the
         * tree `package-toolchains.sh` archives, and round up.
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
            descriptionRes = R.string.toolchain_ruby_description,
            // Shared conservative figures cover both native targets. ARM's usr/
            // tree is 36,736 KiB and its ZIP is 10,421,167 bytes; x86_64 measures
            // 36,916 KiB and 10,589,902 bytes. The rounded unpacked estimate and
            // transfer fallback cover the larger build of each.
            estimatedSize = 39_000_000,
            downloadSize = 10_700_000,
            downloadUrl = "https://github.com/rmyndharis/VSCodroid/releases/latest/download/toolchain_ruby.zip",
        ),
        ToolchainInfo(
            packName = "toolchain_java",
            displayName = "Java 17",
            shortLabel = "Java 17",
            descriptionRes = R.string.toolchain_java_description,
            // Shared conservative figures cover both native targets. ARM's usr/
            // tree is 151,840 KiB and x86_64 is 153,192 KiB; 158 MB reserves
            // enough for the larger rounded tree before the copy starts. The
            // x86_64 ZIP measures 53.7 MB; the 56.5 MB fallback still covers it.
            estimatedSize = 158_000_000,
            downloadSize = 56_500_000,
            downloadUrl = "https://github.com/rmyndharis/VSCodroid/releases/latest/download/toolchain_java.zip",
        ),
    )

    /** Look up toolchain info by pack name (e.g. "toolchain_ruby") or short name (e.g. "ruby"). */
    fun find(nameOrPack: String): ToolchainInfo? =
        available.find { it.packName == nameOrPack || it.packName == "toolchain_$nameOrPack" }

    // There was a `formatSize` here, dividing by 1,000,000 and writing "MB"
    // while every other byte figure the app showed divided by 1,048,576 and
    // wrote the same word, so "MB" meant one thing on the toolchain cards and
    // another, 4.9% larger, everywhere the user could compare it against. The
    // divisor was right and having its own copy of it was not: the cards go
    // through [com.vscodroid.util.StorageManager.formatSize] now, which is
    // decimal for every screen at once and says why.
}
