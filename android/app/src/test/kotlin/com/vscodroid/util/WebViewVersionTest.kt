package com.vscodroid.util

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The WebView floor, checked in both directions and pinned to the documents
 * that state it.
 *
 * The defect this covers was not a wrong comparison, it was the absence of one:
 * the project stated Chrome 105 in the README and as NFR-COMPAT-04 while no code
 * read the installed version at all. A test that only exercised the comparison
 * would therefore miss what actually went wrong, which is the documents and the
 * constant drifting apart. [minimumMatchesTheDocumentsThatStateIt] is the half
 * that catches that.
 */
class WebViewVersionTest {

    // ---- parsing -----------------------------------------------------------

    @Test
    fun `a full Chrome version yields its major`() {
        assertEquals(105, WebViewVersion.majorVersionOf("105.0.5195.79"))
        assertEquals(131, WebViewVersion.majorVersionOf("131.0.6778.200"))
    }

    @Test
    fun `a bare major is read as one`() {
        assertEquals(105, WebViewVersion.majorVersionOf("105"))
    }

    @Test
    fun `surrounding whitespace does not defeat parsing`() {
        assertEquals(120, WebViewVersion.majorVersionOf("  120.0.6099.230 "))
    }

    /**
     * Every shape meaning "not answered" has to come back null rather than zero
     * or a default, because [WebViewVersion.isBelowMinimum] turns null into "do
     * not warn" and any number into a verdict.
     */
    @Test
    fun `an unreadable version is null rather than a number`() {
        assertNull(WebViewVersion.majorVersionOf(null), "no package installed")
        assertNull(WebViewVersion.majorVersionOf(""), "empty string")
        assertNull(WebViewVersion.majorVersionOf("   "), "whitespace only")
        assertNull(WebViewVersion.majorVersionOf("dev-build"), "not a number")
        assertNull(WebViewVersion.majorVersionOf(".105"), "leading dot")
        assertNull(WebViewVersion.majorVersionOf("0.0.0.0"), "zero is not a Chrome version")
    }

    // ---- the verdict, in both directions -----------------------------------

    @Test
    fun `a version below the floor is reported`() {
        assertTrue(WebViewVersion.isBelowMinimum("104.0.5112.97"))
        assertTrue(WebViewVersion.isBelowMinimum("88.0.4324.181"))
    }

    @Test
    fun `the floor itself passes`() {
        assertFalse(
            WebViewVersion.isBelowMinimum("105.0.5195.79"),
            "105 is the minimum, not the first rejected version",
        )
    }

    @Test
    fun `a version above the floor passes`() {
        assertFalse(WebViewVersion.isBelowMinimum("131.0.6778.200"))
    }

    /**
     * The direction that costs a user something they cannot act on: being told
     * to update a WebView that may well be current, because its version string
     * was not one we could read.
     */
    @Test
    fun `an unreadable version does not accuse the device`() {
        assertFalse(WebViewVersion.isBelowMinimum(null), "no WebView package")
        assertFalse(WebViewVersion.isBelowMinimum(""), "empty version")
        assertFalse(WebViewVersion.isBelowMinimum("dev-build"), "unparseable version")
    }

    // ---- the constant against the documents that state it ------------------

    /**
     * Reads the floor out of the two documents that state it to a reader and
     * fails when either disagrees with the constant.
     *
     * Every file is required to exist and to contain a figure. A test that
     * silently passed when it could not find the claim would restore exactly the
     * condition being fixed: a stated requirement with nothing holding it.
     *
     * Two other documents name a Chrome version and are deliberately left out.
     * `docs/07-TESTING_STRATEGY.md` lists 105, 120 and 131 as a matrix of
     * versions to test against, so its numbers are not a claim about the floor
     * and matching them here would fail on a non-subject hit.
     * `docs/08-RISK_MATRIX.md` is a historical document whose T04 row names
     * `MainActivity.checkWebViewVersion`, so a reader there already has a path
     * back to the code.
     */
    @Test
    fun minimumMatchesTheDocumentsThatStateIt() {
        val claim = Regex("""Chrome (\d+)\+?""")
        val stated = listOf("../../README.md", "../../docs/02-SRS.md", "../../docs/05-API_SPEC.md")
        for (path in stated) {
            val file = File(path)
            assertTrue(file.isFile, "$path is missing; this test cannot check what it claims")
            val found = claim.findAll(file.readText()).map { it.groupValues[1].toInt() }.toSet()
            assertTrue(
                found.isNotEmpty(),
                "$path states no Chrome version, so the requirement it is supposed to carry " +
                    "has quietly disappeared",
            )
            assertEquals(
                setOf(WebViewVersion.MINIMUM_CHROME_MAJOR),
                found,
                "$path states Chrome $found while WebViewVersion.MINIMUM_CHROME_MAJOR is " +
                    "${WebViewVersion.MINIMUM_CHROME_MAJOR}; move both together",
            )
        }
    }
}
