package com.vscodroid.util

/**
 * The WebView version this project states as its floor, and the parsing that
 * decides whether an installed one is below it.
 *
 * Kept out of the Activity that shows the warning so the decision can be tested
 * without a device. The framework call naming the installed package is the only
 * part that needs one, and it is a single line at the call site.
 *
 * The floor is stated in three places a reader can reach (`README.md`, and
 * NFR-COMPAT-04 in `docs/02-SRS.md`) and for a year was enforced in none, which
 * is what [MINIMUM_CHROME_MAJOR] and the test pinning it to those documents
 * exist to prevent recurring.
 */
object WebViewVersion {

    /**
     * Chrome 105, the version `minSdk` 33 ships with.
     *
     * Raising this means raising it in the documents too; `WebViewVersionTest`
     * reads them and fails when the three disagree.
     */
    const val MINIMUM_CHROME_MAJOR = 105

    /**
     * The Chrome major version in a WebView package's `versionName`, or null
     * when there is no version or it is not shaped like one.
     *
     * Null is deliberately not "too old". An unreadable version means the
     * question went unanswered, and treating that as a failure would accuse
     * every device whose WebView reports something unexpected. The check this
     * feeds can be wrong in two directions, and a false accusation is the
     * costlier one: it sends someone to update a component that is already
     * current.
     */
    fun majorVersionOf(versionName: String?): Int? =
        versionName?.trim()?.substringBefore('.')?.toIntOrNull()?.takeIf { it > 0 }

    /** True only when a version was read *and* it is below the floor. */
    fun isBelowMinimum(versionName: String?): Boolean {
        val major = majorVersionOf(versionName) ?: return false
        return major < MINIMUM_CHROME_MAJOR
    }
}
