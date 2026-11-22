package com.vscodroid.webview

import android.content.Context
import android.system.Os
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File

/** The two names Android gives the data directory of user 0, and that of a second user. */
private const val USER_DATA = "/data/user/0/com.vscodroid"
private const val LEGACY_DATA = "/data/data/com.vscodroid"
private const val SECOND_USER_DATA = "/data/user/10/com.vscodroid"

/**
 * The data directory has two names for user 0, `/data/user/0/<package>` (what every Context getter answers) and
 * `/data/data/<package>` (what a person or a workspace file may write). Where they are separate mounts of one
 * directory, canonicalisation does not turn one into the other, so a location that must stay unreadable has to be
 * recognised under both, or a folder named the other way is published whole.
 *
 * The paths are synthetic and exist on no build machine, so canonicalising them changes nothing and every assertion
 * is about the comparison rather than about the host.
 */
class DataDirectorySpellingTest {

    private val warnings = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.w(any(), capture(warnings), any()) } just Runs
        every { Logger.w(any(), capture(warnings)) } just Runs
        every { Logger.d(any(), any()) } just Runs
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun contextAt(dataDir: String): Context {
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns File("$dataDir/files")
        every { context.dataDir } returns File(dataDir)
        every { context.noBackupFilesDir } returns File("$dataDir/no_backup")
        every { context.cacheDir } returns File("$dataDir/cache")
        every { context.getExternalFilesDir(null) } returns null
        return context
    }

    /** The fixture's premise: the set holds each location as the Context's getters name it. */
    @Test
    fun `the sensitive set holds the ssh directory as the context names it`() {
        val sensitive = sensitiveLocations(contextAt(USER_DATA))

        assertTrue("$USER_DATA/files/home/.ssh" in sensitive, "the fixture does not reach the ssh directory: $sensitive")
    }

    @ParameterizedTest(name = "{0} judged against a context at {1}")
    @CsvSource(
        "$LEGACY_DATA/files/home, $USER_DATA",
        "$USER_DATA/files/home, $LEGACY_DATA",
        "$LEGACY_DATA/files/home/.ssh, $USER_DATA",
        "$LEGACY_DATA/files, $USER_DATA",
        "$LEGACY_DATA/files/home, $SECOND_USER_DATA",
    )
    fun `a folder named through the other spelling of the data directory is not published`(
        candidate: String, dataDir: String,
    ) {
        assertNull(workspaceRootOrNull(candidate, sensitiveLocations(contextAt(dataDir))))
    }

    /** The control: recognising the other spelling refuses only what overlaps a sensitive location. */
    @Test
    fun `an ordinary folder named through the other spelling is still published`() {
        val candidate = "$LEGACY_DATA/files/projects/app"

        assertEquals(candidate, workspaceRootOrNull(candidate, sensitiveLocations(contextAt(USER_DATA))))
    }

    /**
     * What a workspace file in a cloned repository can ask for: its `folders` list is published as written, so a
     * folder spelled `/data/data/<package>/files/home` would serve the ssh key, the key that opens the sealed
     * extension secrets, and the WebView's own stores.
     */
    @Test
    fun `a workspace member named through the other spelling serves no credential`() {
        val context = contextAt(USER_DATA)
        val sensitive = sensitiveLocations(context)
        val roots = workspaceResourceRootsInForce(
            publishedResourceRoots(context, sensitive), sensitive, null,
            listOf("$LEGACY_DATA/files/home", LEGACY_DATA, "$LEGACY_DATA/files/projects/app"),
        )

        listOf(
            "$LEGACY_DATA/files/home/.ssh/id_ed25519",
            "$LEGACY_DATA/no_backup/secret-storage.key",
            "$LEGACY_DATA/app_webview_vscodroid/Default/Local Storage/leveldb/000003.log",
            "$LEGACY_DATA/files/home/.vscodroid/data/token",
        ).forEach { assertEquals(ResourceOutcome.Refused, resourceOutcome(it, roots), "$it was published: $roots") }
        assertEquals(
            ResourceOutcome.Missing, resourceOutcome("$LEGACY_DATA/files/projects/app/diagram.png", roots),
            "the ordinary member was refused with the others",
        )
    }

    /**
     * `~/projects` is read as written, so a link a person wrote with the other spelling names the projects folder
     * that way, and a `projects` inside `~/.claude` passed the overlap test and was published whole. The warning
     * names the location as the app knows it, not where the link points.
     */
    @Test
    fun `a projects link written with the other spelling into a sensitive folder is not published`() {
        val context = contextAt(USER_DATA)
        mockkStatic(Os::class)
        every { Os.readlink("$USER_DATA/files/home/projects") } returns "$LEGACY_DATA/files/home/.claude/projects"

        val roots = publishedResourceRoots(context)

        assertFalse("$LEGACY_DATA/files/home/.claude/projects" in roots, "a folder inside ~/.claude was published: $roots")
        assertTrue("$USER_DATA/files/server" in roots, "the other roots went with it: $roots")
        assertTrue(warnings.any { "~/.claude" in it }, "the log does not name the location: $warnings")
    }
}
