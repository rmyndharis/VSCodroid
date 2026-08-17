package com.vscodroid.util

import java.io.InputStream

/**
 * The third-party attribution documents, as they are carried inside the APK.
 *
 * The app redistributes GPL and LGPL binaries: Bash, Git, GNU Make, readline,
 * libiconv, gdbm, liblzma and zstd, among others. The GPL's written offer of
 * corresponding source has to accompany the binary, and until these were bundled
 * it accompanied nothing. Both documents existed, both were complete, and both
 * lived only in the repository, which is not somewhere the holder of an installed
 * APK has any reason to look.
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
        BUNDLED.joinToString(SEPARATOR) { name ->
            try {
                open(name).bufferedReader().use { it.readText() }
            } catch (_: Exception) {
                missingMarker(name)
            }
        }

    /** What stands in for a document this build did not package. */
    fun missingMarker(name: String) = "[$name is missing from this build]"

    /** Blank lines between documents, so two markdown files do not run together. */
    private const val SEPARATOR = "\n\n\n"
}
