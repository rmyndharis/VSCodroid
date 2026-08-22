package com.vscodroid.webview

import com.vscodroid.util.Logger
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * How often the refusal of a workspace is reported, which is a different question
 * from whether it is reported.
 *
 * `resourceRootsInForce` is called per webview resource request, not per
 * navigation: `interceptCdnRequest` invokes the open-folder supplier on the
 * extension-resource arm, and one markdown preview or notebook render makes
 * dozens to hundreds of those. The refusal itself is sticky, because the supplier
 * answers the same folder for as long as it stays open, so a user who opens `~`
 * (the terminal's own cwd, and where the workbench's Open Folder dialog starts)
 * turned every preview into a burst of identical `Logger.w` lines, each
 * repeating the absolute path. `Logger.w` is not gated on a debuggable build, so
 * that shipped, and unbounded log growth driven by page activity is what a bug
 * report has to be read out of.
 *
 * Each case uses a path of its own. The memo lives for the life of the process
 * and is keyed on the candidate, so two cases sharing a folder would be one case
 * with a hidden dependency on the order they ran in.
 *
 * NEGATIVE CONTROL: drop the `candidate != lastRefusedWorkspace` test in
 * `resourceRootsInForce` and `a refused workspace is reported once` goes red at
 * three lines instead of one, while the two cases below it stay green.
 */
class WorkspaceRefusalLogTest {

    private val logged = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        logged.clear()
        mockkObject(Logger)
        every { Logger.w(any(), any()) } answers { logged += secondArg<String>() }
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /** A folder holding a sensitive location, so every call refuses it. */
    private fun refuse(home: String, times: Int) = repeat(times) {
        val roots = resourceRootsInForce(
            listOf("/nowhere/published"), listOf("$home/.ssh"), home
        )
        assertEquals(
            listOf("/nowhere/published"), roots,
            "the fixture stopped refusing the workspace, so the count below means nothing",
        )
    }

    @Test
    fun `a refused workspace is reported once`() {
        refuse("/x/once/home", times = 3)

        assertEquals(
            1, logged.size,
            "the refusal is reported per resource request rather than per folder, and a " +
                "preview makes hundreds of those:\n" + logged.joinToString("\n") { "  $it" },
        )
        assertTrue(
            logged.single().contains("/x/once/home"),
            "the one line left has to say which folder was refused: ${logged.single()}",
        )
    }

    /**
     * The complement, and the half that a memo is easy to get wrong: a second
     * folder is a second thing to explain.
     */
    @Test
    fun `a different workspace is reported on its own`() {
        refuse("/x/first/home", times = 2)
        refuse("/x/second/home", times = 2)

        assertEquals(
            2, logged.size,
            "one folder's refusal silenced another's:\n" + logged.joinToString("\n") { "  $it" },
        )
    }

    /**
     * And reopening the same folder after closing it, which is what clearing the
     * memo on an accepted or absent candidate is for. Without it the explanation
     * is given once per process and never again, which is worse than the noise it
     * replaced.
     */
    @Test
    fun `reopening a refused workspace is reported again`() {
        refuse("/x/reopened/home", times = 1)
        // The folder closed: the supplier answers null until the next navigation.
        resourceRootsInForce(listOf("/nowhere/published"), listOf("/x/reopened/home/.ssh"), null)
        refuse("/x/reopened/home", times = 1)

        assertEquals(
            2, logged.size,
            "the user was told once and then met with silence:\n" +
                logged.joinToString("\n") { "  $it" },
        )
    }

    /** The control: an ordinary folder is still silent, which is the whole point. */
    @Test
    fun `an accepted workspace says nothing`() {
        val roots = resourceRootsInForce(
            listOf("/nowhere/published"), listOf("/x/accepted/home/.ssh"),
            "/x/accepted/home/projects/app",
        )

        assertTrue(
            roots.contains("/x/accepted/home/projects/app"),
            "the fixture refused the workspace, so its silence proves nothing",
        )
        assertTrue(logged.isEmpty(), "an ordinary folder was reported: $logged")
    }
}
