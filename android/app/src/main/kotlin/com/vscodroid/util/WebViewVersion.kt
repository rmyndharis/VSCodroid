package com.vscodroid.util

/**
 * The WebView version this project states as its floor, and the parsing that
 * decides whether an installed one is below it.
 *
 * Kept out of the Activity that shows the warning so the decision can be tested
 * without a device. The framework call naming the installed package is the only
 * part that needs one, and it is a single line at the call site.
 *
 * The floor is stated in three places a reader can reach (`README.md`,
 * NFR-COMPAT-04 in `docs/02-SRS.md`, and `docs/05-API_SPEC.md`) and for a year
 * was enforced in none, which is what [MINIMUM_CHROME_MAJOR] and the test
 * pinning it to those documents exist to prevent recurring.
 */
object WebViewVersion {

    /**
     * Chrome 125, the first with CSS anchor positioning.
     *
     * The workbench places every webview, a Markdown preview, an extension's
     * panel such as a chat view, a notebook's outputs, with
     * `OverlayLayoutElement` (`src/vs/base/browser/overlayLayoutElement.ts`,
     * in Code - OSS since at least 1.133.0). It puts the webview over its
     * editor with `anchor()` and `anchor-size()` and has no other way to, so
     * below 125 the webview keeps its default place and size, under the bottom
     * of the page, and its tab stays blank while the rest of the editor works.
     * Measured on an API 34 emulator image with WebView 113. This was 105, the
     * version `minSdk` 33 shipped with, which let exactly those devices through
     * without a word.
     *
     * Raising this means raising it in the documents too; `WebViewVersionTest`
     * reads them and fails when the three disagree.
     */
    const val MINIMUM_CHROME_MAJOR = 125

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

    /**
     * Whether to tell the user now: below the floor, and not already told about
     * this same version.
     *
     * Once per installed version rather than at every launch, because the
     * warning names a fix that is not always the user's to make (WebView updates
     * can be blocked by the device's owner), and a dialog over every start of an
     * editor that still works is a cost of its own. A different version, an
     * update that is still too old or a downgrade, is told again.
     */
    fun shouldWarn(versionName: String?, lastWarned: String?): Boolean =
        isBelowMinimum(versionName) && versionName != lastWarned
}
