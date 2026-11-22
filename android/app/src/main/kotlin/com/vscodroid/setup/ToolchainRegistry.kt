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
        /** Short label for the toolchain card (e.g. "Ruby", "Java 25"). */
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
         * rather than estimate: the "Unpacked" line `package-toolchains.sh` prints
         * is the tree it archives in 4 KiB blocks (`du -sk
         * android/toolchain_<name>/src/main/assets/usr` gives the same), and
         * this figure is that rounded up with room for the next Termux build:
         * the script refuses a tree above it.
         */
        val estimatedSize: Long,
        /**
         * Approximate size of the ZIP fetched over HTTP, in bytes: the ZIP
         * `package-toolchains.sh` builds from the pack, rounded up with room for
         * the next Termux build. The script refuses a ZIP above it.
         *
         * Measured from the release assets rather than computed: a payload is
         * rebuilt whenever its source moves, so both figures here are
         * hand-written and go stale the same way. The card and the picker quote
         * it to a person deciding whether to download over mobile data, so
         * understating it tells them less than the truth. It also enters the
         * HTTP install's free-space pre-flight ([toolchainInstallBytes] adds it to
         * the unpacked tree as one of its two terms), so a figure below the ZIP
         * asks for a little less room than the ZIP needs. `downloadFile`
         * prefers the length the server actually sent and falls back to this
         * only when there is none, so a download never depends on it.
         */
        val downloadSize: Long,
        /** Fallback URL for sideloaded installs (no Play Store). Null = Play-only. */
        val downloadUrl: String? = null,
        /**
         * Whether the first-run picker offers it. The picker asks "What do you
         * code in?" and lists languages; a toolchain that is a tool and not a
         * language is offered on the management screen alone, where nobody is
         * answering that question.
         */
        val offeredInPicker: Boolean = true,
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
            // Measured on the v1.10.0 build (Termux ruby 4.0.10-1): the pack's
            // `usr/` tree is 39,682,048 bytes in 4 KiB blocks, so block rounding
            // is most of the gap to the file contents, and the ZIP is 10,971,125
            // bytes. Termux moved ruby from 3.4.1 to 4.0.6 and the tree grew past
            // the 36,000,000 recorded then, which package-toolchains.sh refuses
            // rather than shipping a card that admits a device to an install it
            // cannot finish; it grew again by the C headers a native gem is
            // built against (`usr/include/ruby-4.0.0`). That tree was stripped by
            // an earlier rule, which deleted every directory named doc, ri, test,
            // tests or spec anywhere under `usr/lib/ruby`. download-ruby.sh now
            // deletes doc, test, tests and spec only at a gem's root, and the
            // generated rdoc and ri only under gems/<abi>/doc, so the pack keeps
            // the code that lives in such directories (test-unit's lib/test,
            // rdoc's lib/rdoc/ri, rbs's lib/rbs/test, bundler's newgem templates)
            // and is larger than the tree measured here. Both figures were set
            // about 3 percent over that measurement, which says nothing about the
            // pack the current rule makes: measure again before a release.
            estimatedSize = 41_000_000,
            downloadSize = 11_300_000,
            downloadUrl = "https://github.com/rmyndharis/VSCodroid/releases/latest/download/toolchain_ruby.zip",
        ),
        ToolchainInfo(
            packName = "toolchain_java",
            displayName = "Java 25",
            shortLabel = "Java 25",
            descriptionRes = R.string.toolchain_java_description,
            // Measured on the v1.10.0 build (Termux openjdk-25 25.0.5.1): the pack's
            // `usr/` tree is 185,065,472 bytes in 4 KiB blocks and the ZIP is
            // 68,783,186 bytes. The pack carried OpenJDK 21 until 25, the current
            // LTS, replaced it, and 25 is 5.5 MB larger. `download-java.sh` copies
            // with `-RL`, which dereferences the symlinks of `legal/`, and
            // `package-toolchains.sh` refuses a tree or a ZIP above these figures
            // rather than shipping a card that admits a device to an install it
            // cannot finish. They leave about 4 percent (tree) and 2 percent (ZIP)
            // for a point release of openjdk-25, which Termux has made before.
            estimatedSize = 192_000_000,
            downloadSize = 70_000_000,
            downloadUrl = "https://github.com/rmyndharis/VSCodroid/releases/latest/download/toolchain_java.zip",
        ),
        ToolchainInfo(
            packName = "toolchain_cpp",
            displayName = "C/C++",
            shortLabel = "C/C++",
            descriptionRes = R.string.toolchain_cpp_description,
            // Measured on the v1.10.0 build (Termux clang 21.1.8-3): the pack's
            // `usr/` tree is 272,781,312 bytes in 4 KiB blocks and the ZIP is
            // 79,112,217 bytes. libLLVM.so and libclang-cpp.so are most of it, then
            // the headers, clangd and the clang resource directory. The figures
            // leave about 3 percent for the next point release of clang:
            // package-toolchains.sh refuses to zip a tree or a ZIP above them, and
            // a release tag is where that would be found.
            //
            // This is also the largest install pre-flight of the language toolchains. A first
            // install over HTTP holds the staged copy and the one in `usr/` at
            // once, so it asks for about twice this figure plus the buffer.
            estimatedSize = 280_000_000,
            downloadSize = 81_000_000,
            downloadUrl = "https://github.com/rmyndharis/VSCodroid/releases/latest/download/toolchain_cpp.zip",
        ),
        ToolchainInfo(
            packName = "toolchain_codex",
            displayName = "Codex",
            shortLabel = "Codex",
            descriptionRes = R.string.toolchain_codex_description,
            // Measured on the v1.10.0 build (Termux codex 0.171.0): the pack's `usr/`
            // tree is 374,263,808 bytes in 4 KiB blocks, nearly all of it two files,
            // the engine (298.0 MB) and its code-mode helper (76.2 MB), and the ZIP
            // is 145,475,266 bytes. Termux took codex through 0.169.1, 0.170.0 and
            // 0.171.0 in the weeks before 1.10.0 and the step from 0.170.0 added
            // about 17 MB of tree, so these two figures leave 5 percent over what
            // was measured, and an earlier version of them, 0.7 MB over, did not
            // survive that. Termux takes whatever the package is on the day the
            // pack is built, and package-toolchains.sh refuses a tree or a ZIP
            // above them at tag time. Measure the pack again before a release: a
            // larger build can still outgrow them.
            estimatedSize = 395_000_000,
            downloadSize = 153_000_000,
            downloadUrl = "https://github.com/rmyndharis/VSCodroid/releases/latest/download/toolchain_codex.zip",
            offeredInPicker = false,
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
