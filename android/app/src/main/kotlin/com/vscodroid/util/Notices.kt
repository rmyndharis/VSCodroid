package com.vscodroid.util

import java.io.InputStream

/**
 * The third-party attribution documents and licence texts carried inside the APK.
 *
 * The app redistributes GPL and LGPL binaries: Bash, Git, GNU Make, readline,
 * libiconv, gdbm, liblzma and zstd, among others. The GPL's written offer of
 * corresponding source has to accompany the binary, and until these were bundled
 * it accompanied nothing. Both documents existed, both were complete, and both
 * lived only in the repository, which is not somewhere the holder of an installed
 * APK has any reason to look.
 *
 * The offer is one of two obligations, and [LICENSE_TEXTS] is the other: a copy
 * of the licence itself has to travel with the binary as well.
 *
 * The files are packaged by the `bundleNotices` task in `app/build.gradle.kts`,
 * which copies them from the repository root rather than keeping a second copy in
 * `src/main/assets`. They are read from the APK on demand and never extracted, so
 * they cost nothing on disk and nothing in the first-run storage pre-flight.
 */
object Notices {

    /**
     * Asset names, in reading order.
     *
     * These are basenames because that is what a Gradle `Copy` produces: the
     * sources are `NOTICE.md` at the repository root and `docs/LEGAL_NOTICES.md`.
     * `NoticesTest` holds this list and the task's `from(...)` lines to each
     * other, because a rename on either side is silent otherwise. The dialog
     * would simply come up empty, on a device, with every gate green.
     */
    val BUNDLED = listOf("NOTICE.md", "LEGAL_NOTICES.md")

    /**
     * The verbatim licence texts, display name to asset basename.
     *
     * GPL-2.0 section 1, GPL-3.0 section 4, LGPL-2.1 section 1 and LGPL-3.0
     * section 4 each require a copy of the licence to be given to whoever
     * receives the binary. Every one of those licences is redistributed here:
     * Git and Zstandard under GPL-2.0; Bash, GNU Make, readline and gdbm under
     * GPL-3.0; libiconv and liblzma under LGPL-2.1; GMP under LGPL-3.0.
     *
     * GMP is why the fourth text is here. It ships in the Ruby toolchain pack
     * rather than the base APK, so only a device that installed Ruby holds the
     * library, but the pack has no licence screen of its own: this dialog is the
     * only place a copy of LGPL-3.0 can reach that device. LGPL-3.0 is drafted
     * as additional permissions on top of GPL-3.0 and its section 4 asks for
     * both documents, so it is the supplement that was missing rather than the
     * whole obligation.
     *
     * The licence column of the inventory in `docs/LEGAL_NOTICES.md`, and
     * `TOOLCHAIN_LIBRARIES` in `scripts/check-library-attribution.py`, are where
     * to re-derive this set when the bundled binaries change; that script fails
     * a build whose components carry a copyleft licence with no text here.
     *
     * Deliberately not in [BUNDLED]. These are 78 KiB of legalese that nobody
     * opens the notices screen to read, and appending them to the body would
     * bury the attribution and the source offer under a screenful-per-scroll of
     * licence. They are one tap away from the same dialog instead, which keeps
     * that screen as readable as it was and still hands over the texts.
     *
     * Order is the reading order of the chooser, so this is a `LinkedHashMap`.
     */
    val LICENSE_TEXTS = linkedMapOf(
        "GNU General Public License v2.0" to "COPYING.GPLv2",
        "GNU General Public License v3.0" to "COPYING.GPLv3",
        "GNU Lesser General Public License v2.1" to "COPYING.LGPLv2.1",
        "GNU Lesser General Public License v3.0" to "COPYING.LGPLv3"
    )

    /**
     * Every bundled document, concatenated.
     *
     * [open] is the asset reader, normally `context.assets::open`. It is a
     * parameter so this can be exercised without an `AssetManager`.
     *
     * A document that cannot be read is replaced by a line saying so rather than
     * skipped. Skipping is the dangerous behaviour here: dropping
     * `LEGAL_NOTICES.md` would remove the source offer while still producing a
     * plausible-looking notices screen, and nobody reading it would know that
     * half of it was missing. The replacement goes on the screen rather than into
     * logcat for the same reason, and it is why nothing here logs.
     */
    fun read(open: (String) -> InputStream): String =
        BUNDLED.joinToString(SEPARATOR) { readOne(it, open) }

    /**
     * One packaged document, or [missingMarker] in place of one that will not
     * open. Never throws, for the reason [read] does not: the caller is a dialog
     * with nowhere to put an exception except a crash.
     */
    fun readOne(name: String, open: (String) -> InputStream): String =
        try {
            open(name).bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            missingMarker(name)
        }

    /** What stands in for a document this build did not package. */
    fun missingMarker(name: String) = "[$name is missing from this build]"

    /** Blank lines between documents, so two markdown files do not run together. */
    private const val SEPARATOR = "\n\n\n"
}
